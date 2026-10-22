// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import com.example.interview.ai.DemeanorAnalysisService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// 报告生成异步服务
// ============================================================
// 为什么把 @Async 放在服务方法上，而不是直接放在 Controller 方法上：
//   1. Controller 方法身上挂着请求上下文（SecurityContext、请求属性），切线程后会丢；
//      这里只接收 sessionId 和 reportId 两个纯净参数，异步线程不需要任何请求上下文；
//   2. 异步逻辑可以单独测：直接 new 出来调 generate，不必经过 HTTP。
// 为什么用独立线程池 reportGenerationTaskExecutor（AsyncConfig 里已配好）：
//   报告生成 = "拼一段长文本 + 一次 10~30 秒的 AI 调用"，和评分、语音转写混用同一个池
//   会互相排队；单独一个池（核心 2 / 最大 4 / 队列 30）能把这种长尾调用隔离开。
// 状态约定：成功写 COMPLETED，失败写 FAILED——前端只认这两个终态。
// ============================================================
@Service
public class ReportGenerationAsyncService {

    // 日志对象：异步线程里抛出的异常不会回到请求线程，失败只能靠日志留痕。
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

    // 五个协作者全部构造器注入，字段 final：异步线程读到的依赖在装配期就固定了，不会中途被换掉。
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
     * 异步生成报告：组装上下文 → 调 AI → 写回正文并置 COMPLETED；任何异常置 FAILED。
     *
     * <p>为什么入参是 sessionId + reportId 两个：sessionId 用来查数据，reportId 用来定位
     * 要更新的那一行。记录是 Controller 那边建好的，这里只负责"填内容 + 改状态"。</p>
     *
     * @param sessionId 面试会话 ID
     * @param reportId  报告记录的主键 ID
     */
    // 显式指定线程池名。不指定的话 Spring 会退回默认的 SimpleAsyncTaskExecutor——
    // 它每次调用新建一个线程且不复用，报告生成这种长任务跑几次就能把机器拖垮。
    @Async("reportGenerationTaskExecutor")
    public void generate(UUID sessionId, Long reportId) {
        // 第一步：按主键取报告记录。取不到就直接返回——正常不会发生（Controller 刚插过），
        // 但记录被并发删除时，若继续往下走就会在 entity.setStatus 处抛 NPE，
        // 而 NPE 发生在 try 之外，日志里什么都留不下。所以先挡一道。
        ReportEntity entity = reports.selectById(reportId);
        if (entity == null) {
            return;
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

        } catch (Exception e) {
            // 失败也必须落状态：前端靠 FAILED 显示"生成失败，请重试"。
            // 不写的话记录会永远停在 GENERATING，用户看到的是一直转圈。
            entity.setStatus("FAILED");
            reports.updateById(entity);
            // 异常本身一定要记日志。异步线程的堆栈不会冒泡到任何请求里，
            // 不记日志就无法区分到底是 AI 超时、还是上下文组装时查库失败。
            log.error(
                    "report generation failed, sessionId={}",
                    sessionId,
                    e
            );
        }
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
