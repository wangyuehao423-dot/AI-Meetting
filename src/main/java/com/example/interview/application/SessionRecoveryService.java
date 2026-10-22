package com.example.interview.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// ============================================================
// SessionRecoveryService —— 会话主动恢复：重连时把中断的异步步骤幂等补跑
// ============================================================
// 这是"亮点2"里原本缺失的一块：既有实现是"被动/惰性恢复"——Mongo 权威源、
// Redis 只加速、下次 load 自动从 Mongo 读回，配合 processedRequestIds 防重放；
// 但没有任何主动入口在重连时把"已经判定的异步步骤"重新端起来补跑完。
// 本服务补上这一环：一次 recover 调用 = 读当前快照 → 按状态判定"哪些异步步骤
// 可能被丢了" → 幂等地把它们重新触发，绝不重复造数据。
//
// 幂等性论证（为什么补跑不会造成第二条脏数据）：
//   · 下一题补跑走 NextQuestionPreGeneratorService.preGenerate —— "先查后插 +
//     唯一键兜底"，同一会话同一序号只允许一行，重复触发自动跳过；
//   · 报告补跑走 ReportGenerationAsyncService.generate —— 记录按 session_id 唯一，
//     补跑只是把已存在但卡在 GENERATING/FAILED 的记录重新生成一遍，不新增行。
// 因此 recover 可以被安全地反复调用（前端重连、定时补偿都行），不影响正确性。
//
// 边界说明：追问为何不在此补跑——追问的生成走 Redis 队列 + 独立消费者，
// 它有自身的消费/补偿机制自愈；更重要是"一道题没有追问"是合法分支（分数段决定），
// 仅凭"追问表缺行"无法区分是中断还是本就无追问，补跑反而会造出本不该有的追问，
// 所以这里主动收回手，只补跑"缺失必产生坏结果"的下一题与报告两件事。
// ============================================================
@Service
public class SessionRecoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(SessionRecoveryService.class);

    private final SessionSnapshotRepository snapshots;
    private final QuestionMapper questions;
    // 从这里开始是新增的：答案表 Mapper，recover 要靠"已作答最大序号"定位预生成缺口。
    private final AnswerEntityMapper answers;
    // 新增结束
    private final ReportMapper reports;
    private final ReportGenerationAsyncService generation;
    private final NextQuestionPreGeneratorService preGenerator;

    public SessionRecoveryService(
            SessionSnapshotRepository snapshots,
            QuestionMapper questions,
            // 从这里开始是新增的：答案表 Mapper 由 Spring 自动注入。
            AnswerEntityMapper answers,
            // 新增结束
            ReportMapper reports,
            ReportGenerationAsyncService generation,
            NextQuestionPreGeneratorService preGenerator
    ) {
        this.snapshots = snapshots;
        this.questions = questions;
        this.answers = answers;
        this.reports = reports;
        this.generation = generation;
        this.preGenerator = preGenerator;
    }

    /**
     * 执行一次会话主动恢复，返回本次补跑了哪些步骤。
     *
     * <p>判定逻辑按会话当前快照状态分派：
     *   · 进行中（QUESTIONING / ANSWERING / JUDGING / ASKING）——补跑"下一题预生成"。
     *     这些状态下必然已有当前题（ACTIVE/PRE_GENERATED），目标序号 = 现有最大序号 + 1，
     *     若那一道因为异步崩溃没生成出来，这里把它重新端起来；已存在则 preGenerate 幂等跳过。
     *   · 已结束（ENDED）——补跑"报告生成"。若报告记录已建、却卡在 GENERATING/FAILED、
     *     说明生成这一步异步中断了，重新触发；记录按 session_id 唯一，不会产生第二条。
     *   · 其它状态（INIT / MATCHING）不动作，避免在流程未成型时越权补跑出脏数据。
     *
     * @param sessionId 面试会话 ID
     * @return 恢复结果：会话当前状态 + 本次实际补跑的步骤列表
     */
    public RecoveryResult recover(UUID sessionId) {
        List<String> actions = new ArrayList<>();
        InterviewSessionSnapshot snapshot = snapshots.load(sessionId);
        String state = snapshot.state().name();
        switch (snapshot.state()) {
            case QUESTIONING, ANSWERING, JUDGING, ASKING ->
                    recoverNextQuestion(sessionId, actions);
            case ENDED ->
                    recoverReport(sessionId, actions);
            default ->
                    // INIT / MATCHING：流程还没成型，主动补跑容易造脏数据，跳过。
                    log.debug("session recovery skipped, sessionId={}, state={}",
                            sessionId, state);
        }
        return new RecoveryResult(state, actions);
    }

    /**
     * 补跑"下一题预生成"：把"已作答最大题号"的后一道重新端起来。
     *
     * <p>为什么按"已作答最大序号 + 1"而不是"题表最大序号 + 1"：
     * 题表的最大序号包含 PRE_GENERATED 行——上一题作答后预生成通常已经把下一题写进库了，
     * 若按题表 max+1 算，recover 会把"下下题"也端起来（超前生成一道没人会答的题，
     * 报告统计也随之错乱）。"已作答最大序号 + 1"才是真正可能被异步中断漏掉的缺口；
     * 若它其实已经生成过，preGenerate 的"先查后插 + 唯一键兜底"会幂等跳过，虚开一枪零副作用。
     */
    private void recoverNextQuestion(UUID sessionId, List<String> actions) {
        // 第一步：查本会话已作答的题目序号（答案表 DISTINCT question_id 反查题表序号）。
        // 追问作答（is_follow_up=1）也指向同一道题，DISTINCT 去重后不会重复计算。
        List<Object> answeredIds = answers.selectObjs(
                Wrappers.<AnswerEntity>query()
                        .select("DISTINCT question_id")
                        .eq("session_id", sessionId.toString())
        );
        int maxAnsweredSeq = 0;
        if (!answeredIds.isEmpty()) {
            Object maxValue = questions.selectObjs(
                    Wrappers.<QuestionEntity>query()
                            .select("COALESCE(MAX(sequence_no), 0)")
                            .in("id", answeredIds)
            ).stream().findFirst().orElse(0);
            maxAnsweredSeq = ((Number) maxValue).intValue();
        }
        // 第二步：补跑缺口 = 已作答最大序号 + 1；preGenerate 幂等兜底。
        int next = maxAnsweredSeq + 1;
        preGenerator.preGenerate(sessionId, next);
        actions.add("requeue next question (sequence=" + next + ")");
    }

    /**
     * 补跑"报告生成"：报告记录已建但卡在非终态时重新触发。
     *
     * <p>为什么只在 ENDED 才补报告：报告本应由用户主动点"生成"触发，状态 ENDED 时若
     * 已存在一条记录却停在 GENERATING（生成线程崩了）或 FAILED（上游失败），用户刷新后
     * 会永远看到一个"正在生成"的空壳。此时重新触发是同一步骤的幂等补跑——记录唯一、
     * 状态被重新置为 GENERATING，绝不多产生一行。
     */
    private void recoverReport(UUID sessionId, List<String> actions) {
        ReportEntity report = reports.selectOne(
                new QueryWrapper<ReportEntity>()
                        .eq("session_id", sessionId.toString())
        );
        if (report == null || "COMPLETED".equals(report.getStatus())) {
            // 没有报告（用户还没点过生成）或已生成完：不属于"中断待补"，无需动作。
            return;
        }
        // 重建生成：异步接口立即返回，不阻塞恢复调用。
        generation.generate(sessionId, report.getId());
        actions.add("re-trigger report generation (previous status="
                + report.getStatus() + ")");
    }

    /**
     * 恢复结果：会话当前状态 + 本次实际补跑的步骤清单。
     */
    public record RecoveryResult(
            String state,
            List<String> actions
    ) {
    }
}