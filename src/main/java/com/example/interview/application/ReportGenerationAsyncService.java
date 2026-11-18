// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import com.example.interview.ai.DemeanorAnalysisService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// 报告生成异步服务
// ============================================================
// 为什么"异步"落在队列上，而不是 @Async 注解上：
//   1. @Async 跑在线程池里，JVM 一崩任务就蒸发，report 行永远停在 GENERATING；
//      走队列后消息没 ack 会自动 requeue，换一个实例接着干；
//   2. 方法只接收 sessionId / reportId 两个纯参数，不需要请求上下文，正好能被消息载荷承载；
//   3. 失败重试、死信、积压可视化都是 broker 现成语义，不用自己写扫库补偿。
// 为什么方法名还叫 AsyncService：调用方（Controller / SessionRecoveryService）与内部逻辑都没变，
//   改名只是好看，却要动一圈引用面；名字保留、语义在 javadoc 里说清。
// 状态约定：成功写 COMPLETED；失败不写终态，返回 false 交给消费者决定重试还是落 FAILED。
// ============================================================
@Service
public class ReportGenerationAsyncService {

    // 日志对象：消费线程里抛出的异常不会回到请求线程，失败只能靠日志留痕。
    private static final Logger log =
            LoggerFactory.getLogger(
                    ReportGenerationAsyncService.class
            );

    // 报告表 Mapper：读记录、写回正文与状态。
    private final ReportMapper reports;
    // 上下文组装器：把整场面试整理成一段 AI 可读的文本。
    private final InterviewContextAssembler contexts;
    // AI 门面：generateReport 内部会截断超长上下文，并复用统一的 chat 调用。
    private final InterviewAiFacade ai;
    // 表现分析：报告生成时对整场作答文本算一次"语言表达分析"，并作为固定章节拼进报告。
    // 为什么放在报告链路而不是放在"结束面试"接口：
    //   1) 结束接口是同步返回的，而分析是一次短则几秒、长可数十秒的 AI 调用，放进异步报告里不阻塞用户；
    //   2) 分析结果天然属于"复盘材料"，和报告同生共死——报告重新生成时分析随之刷新，永远保持一致。
    private final DemeanorAnalysisService demeanor;
    // 答案仓储：取整场作答文本（interview_answer.answer，语音答案提交时已转写成文本）。
    private final AnswerRepository answers;

    // 五个协作者全部构造器注入，字段 final：消费线程读到的依赖在装配期就固定了，不会中途被换掉。
    public ReportGenerationAsyncService(
            ReportMapper reports,
            InterviewContextAssembler contexts,
            InterviewAiFacade ai,
            DemeanorAnalysisService demeanor,
            AnswerRepository answers
    ) {
        this.reports = reports;
        this.contexts = contexts;
        this.ai = ai;
        this.demeanor = demeanor;
        this.answers = answers;
    }

    /**
     * 生成报告：组装上下文 → 调 AI → 写回正文并置 COMPLETED。
     *
     * <p><b>注意：这里已经不是 @Async 方法了。</b>异步的语义挪到了队列上——
     * ReportController 投一条 ReportTask 消息，ReportTaskConsumer 收到后调用本方法。
     * 这个变化补上了一个真实缺口：@Async 跑在线程池里，JVM 一崩，正在生成的任务直接蒸发，
     * report 行永远停在 GENERATING；走队列之后，消息没 ack 就自动 requeue。
     *
     * <p>为什么方法名还叫 AsyncService：调用方（Controller / SessionRecoveryService）
     * 与内部逻辑都没变，改名的收益只是好看，却要动一圈引用面。名字保留、语义在注释里说清。
     *
     * <p><b>返回值语义</b>：true = 成功或无需重试（记录已不存在）；
     * false = 可重试失败，由消费者决定投延迟重试队列还是落死信。
     * 本方法不写 FAILED 终态——重试期间必须停在 GENERATING，否则前端会先闪一下
     * 「生成失败」再跳回「生成中」。终态统一由消费者在重试耗尽时 {@link #markFailed} 落。
     *
     * @param sessionId 面试会话 ID
     * @param reportId  报告记录的主键 ID
     * @return true 表示已处理完（成功或无需重试），false 表示可重试失败
     */
    public boolean generate(UUID sessionId, Long reportId) {
        // 第一步：按主键取报告记录。取不到就直接返回——正常不会发生（Controller 刚插过），
        // 但记录被并发删除时，若继续往下走就会在 entity.setStatus 处抛 NPE，
        // 而 NPE 发生在 try 之外，日志里什么都留不下。所以先挡一道。
        // 记录不存在属于「重试也不会变好」，返回 true 让消费者直接 ack。
        ReportEntity entity = reports.selectById(reportId);
        if (entity == null) {
            return true;
        }

        try {
            // 第二步半：等待在途评分落库。
            // 为什么必须有这一步：评分是异步写回的（答案先插成 SUBMITTED，评分线程算完
            // 再置 JUDGED 并写 score）。用户可能"答完最后一道题立即点生成报告"，若不等
            // 评分写完，上下文组装读到的最后几道题会是"无评分"，AI 生成的报告随之把她们
            // 记成"未作答/无评分"。这里轮询答案状态直到全部 JUDGED；限时 90 秒，
            // 超时则放弃等待让报告先行——评分真卡住时，宁可报告标注样本不足也不无限阻塞。
            long deadline = System.currentTimeMillis() + 90_000L;
            while (answers.hasPendingJudges(sessionId)
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 第二步：组装上下文。查题目、答案、追问三张表并拼成一段文本，
            // 具体怎么查都在实现类里，这里只拿结果字符串。
            String context = contexts.assemble(sessionId);

            // 第三步：调 AI 生成 Markdown 报告。
            // 为什么把 context 传进去，而不是让 AI 自己去查库：AI 没有、也不该有数据库访问权限，
            // 它只能看到我们喂给它的那段文本；输入完全可控，报告出错时也容易复现。
            String markdown = ai.generateReport(
                    sessionId,
                    context
            );

            // 第三步半：把表现分析作为固定章节追加到 AI 报告正文末尾。
            // 为什么用"追加"而不是交给 AI 生成：分析结果已在 DemeanorAnalysisService 里算好、
            // 结构固定且要保证每一次都出现，不依赖 AI 是否记得写这一节。
            // 单独 try-catch：分析失败只丢这一段，绝不能把整份报告拖成 FAILED。
            markdown = appendDemeanor(markdown, sessionId);

            // 第四步：写回正文并按"完成"落状态。
            // 正文和状态必须在同一次 updateById 里写下去：只写正文不改状态，
            // 前端会一直以为还在生成中，轮询永远不结束。
            entity.setContent(markdown);
            entity.setStatus("COMPLETED");
            reports.updateById(entity);
            return true;

        } catch (Exception e) {
            // 这里不写 FAILED：失败可能只是瞬时抖动，消费者会用同一份载荷重试。
            // 若此刻写成终态，重试成功时前端会经历「FAILED → GENERATING → COMPLETED」
            // 的抖动；而如果重试耗尽，消费者会调 markFailed 统一落终态。
            // 异常本身一定要记日志。消费线程的堆栈不会冒泡到任何请求里，
            // 不记日志就无法区分到底是 AI 超时、还是上下文组装时查库失败。
            log.error(
                    "report generation failed, sessionId={}",
                    sessionId,
                    e
            );
            return false;
        }
    }

    /**
     * 兜底置 FAILED：消费者捕获到未预期异常（连 generate 内部的 catch 都没兜住）时调用。
     *
     * <p>为什么需要它：generate 内部已经把 AI 失败收敛成 FAILED，但「selectById 抛异常」
     * 「Mapper 注入失败」这类问题会绕过内部的 catch。没有这一步兜底，
     * report 行会永远停在 GENERATING，前端一直转圈，用户以为系统挂了。
     */
    public void markFailed(Long reportId) {
        ReportEntity entity = reports.selectById(reportId);
        if (entity == null) {
            return;
        }
        entity.setStatus("FAILED");
        reports.updateById(entity);
    }

    /**
     * 把表现分析章节拼到报告正文末尾；分析失败时静默跳过并返回原正文。
     *
     * <p>为什么单独 try-catch：analyze 是又一次 AI 调用，可能因为整场没作答（transcript 为空，
     * 会被判为"没内容"）而跳过，也可能因上游瞬时失败而抛异常。表现分析只是报告的加分项，
     * 它失败不该让主流程辛苦生成好的整份报告一起变 FAILED，所以这里兜住并只记一条 warn。
     *
     * @param markdown  AI 生成的报告正文
     * @param sessionId 面试会话 ID，用于取整场作答文本
     * @return 追加了表现分析章节的正文；分析不可用时原样返回
     */
    private String appendDemeanor(
            String markdown,
            UUID sessionId
    ) {
        try {
            String transcript = answers.loadSessionTranscript(sessionId);
            if (transcript == null || transcript.isBlank()) {
                return markdown;
            }
            var analysis = demeanor.analyze(sessionId, transcript);
            return markdown + "\n\n" + demeanor.toMarkdown(analysis);
        } catch (Exception e) {
            log.warn(
                    "demeanor analysis skipped, sessionId={}",
                    sessionId,
                    e
            );
            return markdown;
        }
    }
}
// 新增结束
