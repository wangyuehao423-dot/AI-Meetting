package com.example.interview.application;

import com.example.interview.ai.FollowUpDecisionService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.FollowUpExecution;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.singleflight.RedisSingleflight;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FollowUpGenerationService {

    // ---- 依赖注入 ---- //

    // 为什么需要这么多依赖（9 个）：
    // 这个方法涉及追问决策、AI 调用、数据库写入、状态机推进、预生成触发
    // 每个依赖对应一个独立的职责
    private final FollowUpDecisionService decisions;
    private final InterviewAiFacade ai;
    private final FollowUpRepository followUps;
    private final SessionSnapshotRepository snapshots;
    private final InterviewSessionStateMachine machine;
    private final NextQuestionPreGeneratorService preGenerator;
    private final FlowExecutor flowExecutor;
    private final RedisSingleflight singleflight;
    private final QuestionMapper questions;
    // 从这里开始是新增的：日志对象，追问链失败但被兜底推进时留痕。
    private static final Logger log =
            LoggerFactory.getLogger(FollowUpGenerationService.class);
    // 新增结束

    public FollowUpGenerationService(
            FollowUpDecisionService decisions,
            InterviewAiFacade ai,
            FollowUpRepository followUps,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            NextQuestionPreGeneratorService preGenerator,
            FlowExecutor flowExecutor,
            RedisSingleflight singleflight,
            QuestionMapper questions
    ) {
        this.decisions = decisions;
        this.ai = ai;
        this.followUps = followUps;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preGenerator = preGenerator;
        this.flowExecutor = flowExecutor;
        this.singleflight = singleflight;
        this.questions = questions;
    }

    /**
     * 生成追问并推进状态。
     *
     * @param task 追问任务，包含会话 ID、题目 ID、评分和重试次数
     *
     * <p>工作流程：
     * 1. 根据评分决定是否需要追问（FollowUpDecisionService.decide 输出三条链：
     *    <60 走 followUpBasicChain、60-79 走 followUpNormalChain、>=80 走 followUpDeepChain；
     *    若未来要支持"高分免追问"，可让 decide 返回 NONE，这里已预留该分支）
     * 2. 如果需要，执行多节点追问链（含 Singleflight 防重保护），
     *    persistFollowUp 节点负责把追问幂等写入 follow_up 表
     * 3. 推进 ASK_COMPLETED 事件
     * 4. 触发下一题预生成
     */
    public void generate(FollowUpTask task) {
        // 从这里开始是修改的：整段生成逻辑包进 try-catch。追问是加分项，
        // 它失败绝不能把状态停死在 ASKING 让用户无限转圈——所以无论成败，
        // 最后都会把状态推进到 QUESTIONING（completeAndPreGenerate 会预生成下一题）。
        try {
            // 第一步：根据评分决定追问策略
            // FollowUpDecisionService.decide 根据分数段返回对应的追问链
            // 如：<60 返回 "followUpBasicChain"（基础追问）
            //     60-79 返回 "followUpNormalChain"（常规追问）
            //     >=80 返回 "followUpDeepChain"（深度追问）
            var plan = decisions.decide((int) task.score());

            // 第二步：如果不需要追问，直接推进状态并预生成下一题
            if ("NONE".equalsIgnoreCase(plan.chain())) {
                completeAndPreGenerate(task.sessionId(), task.questionId());
                return;
            }

            // 第三步：需要追问，执行多节点追问链（含 Singleflight 防重）。
            // 追问的生成与幂等落库都发生在链内：loadQuestionContext 加载原题与作答，
            // 分数段生成节点产出正文，persistFollowUp 节点把结果幂等写入 follow_up 表。
            // 它们落在同一个 fence 作用域内，不会"生成成功却在落库前崩溃"，
            // 因此这里不再单独调 insertIfAbsent——落库由链上的 persistFollowUp 完成。
            executeFollowUpWithSingleflight(
                    task,
                    plan
            );
        } catch (Exception e) {
            // 追问链异常：记日志，仍继续推进状态，保证面试不被追问失败卡死。
            log.error(
                    "follow-up generation failed, continue to next question, "
                            + "sessionId={}, questionId={}",
                    task.sessionId(),
                    task.questionId(),
                    e
            );
        }
        // 修改结束

        // 第四步：无论追问是否成功，都推进状态并预生成下一题（ASKING → QUESTIONING）。
        completeAndPreGenerate(task.sessionId(), task.questionId());
    }

    /**
     * 完成追问阶段并触发预生成。
     *
     * <p>为什么这个方法需要标记为 private：
     * 这是内部逻辑，外部不应该直接调用"完成追问"。
     *
     * <p>流程：
     * 1. 加载当前快照
     * 2. 尝试推进 ASK_COMPLETED 事件
     * 3. 如果成功（changed = true），CAS 更新快照
     * 4. 按追问所属题的序号 + 1 触发下一题预生成（题目缺失时退回会话最大序号 + 1）
     */
    private void completeAndPreGenerate(
            UUID sessionId,
            UUID questionId
    ) {
        // 加载当前快照
        InterviewSessionSnapshot old = snapshots.load(sessionId);

        // 尝试推进 ASK_COMPLETED 事件
        // 为什么用 UUID.randomUUID() 作为事件 ID：
        // 事件 ID 用于幂等性，随机 UUID 确保每次事件是唯一的
        var moved = machine.advance(
                old,
                InterviewEvent.ASK_COMPLETED,
                UUID.randomUUID().toString()
        );

        // 只有状态真的变了才 CAS 更新快照
        // 为什么需要检查 changed：
        // 如果当前状态不允许 ASK_COMPLETED 转换（比如已经是 ANSWERING），
        // advance 返回 changed=false，不应该更新快照
        if (moved.changed()) {
            snapshots.compareAndSet(old, moved.snapshot());
        }

        // 触发下一题预生成：目标序号 = 本道追问所属题的序号 + 1。
        // 为什么不能按"会话内最大序号 + 1"算：上一题的预生成已经把下一题写进库了
        // （题表 max 已被推进到下一题），按 max+1 会跳过真正的下一题、超前生成
        // "下下题"——表现为库里冒出当前题+2 的 PRE_GENERATED，报告统计也随之错乱。
        QuestionEntity current = questions.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getId,
                                questionId.toString()
                        )
        );
        int nextSequence;
        if (current != null && current.getSequenceNo() != null) {
            nextSequence = current.getSequenceNo() + 1;
        } else {
            // 题目不存在（异常兜底）：退回按会话最大序号 + 1 估算。
            QuestionEntity latest = questions.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers
                            .<QuestionEntity>lambdaQuery()
                            .eq(
                                    QuestionEntity::getSessionId,
                                    sessionId.toString()
                            )
                            .orderByDesc(
                                    QuestionEntity::getSequenceNo
                            )
                            .last("limit 1")
            );
            nextSequence = latest == null
                    ? 1
                    : latest.getSequenceNo() + 1;
        }
        preGenerator.preGenerate(sessionId, nextSequence);
    }

    /**
     * 使用 Singleflight 保护执行追问链。
     *
     * <p>RedisSingleflight 确保：
     * - 同一个 key（follow-up:{sessionId}:{questionId}）同时只有一个执行者
     * - 其他并发请求等待第一个请求的结果
     * - 通过 fence 机制防止"过时结果"被写入（类似 CAS 的版本号）
     *
     * @param task 追问任务
     * @param plan 追问计划（包含链名称、层级和目标）
     * @return 生成的追问内容（已由链上 persistFollowUp 节点落库）
     */
    private String executeFollowUpWithSingleflight(
            FollowUpTask task,
            FollowUpDecisionService.FollowUpPlan plan
    ) {
        // 构建 Singleflight key
        // 为什么 key 包含 sessionId 和 questionId：
        // 确保同一个面试的同一道题只有一个追问在执行
        String key = "follow-up:"
                + task.sessionId()
                + ":"
                + task.questionId();

        // 使用 Singleflight 执行（带 fence 保护）
        // Duration.ofMinutes(2) 是超时时间
        // AI 调用通常需要 10-30 秒，2 分钟的超时足够
        var result = singleflight.executeFenced(
                key,
                java.time.Duration.ofMinutes(2),
                () -> {
                    // 这是实际执行追问链的 Lambda
                    // 为什么用 Lambda 而不是方法引用：
                    // executeFenced 需要 Supplier<T>，Lambda 可以传递上下文变量

                    // 构建共享上下文：链首节点会进一步读取原题与作答并实例化内容。
                    FollowUpExecution context =
                            new FollowUpExecution();
                    context.setSessionId(task.sessionId());
                    context.setQuestionId(task.questionId());
                    context.setScore((int) task.score());
                    context.setObjective(plan.objective());
                    // persistFollowUp 节点靠 level 写库（表里只有 level 列），
                    // 必须与入参的追问等级一致。
                    context.setLevel(plan.level());

                    // 执行多节点 LiteFlow 追问链：loadQuestionContext → 生成 → persistFollowUp
                    // 整条链（加载原题/作答、生成正文、幂等落库）都发生在这个 fence 作用域内，
                    // 因此不会出现"生成成功却在落库前崩溃"导致这次追问白跑。
                    LiteflowResponse response =
                            flowExecutor.execute2Resp(
                                    plan.chain(),     // 链名称，如 "followUpDeepChain"
                                    task.sessionId().toString(),  // 请求 ID
                                    context          // 上下文数据
                            );

                    // 检查执行结果
                    if (!response.isSuccess()
                            || context.getContent() == null
                            || context.getContent().isBlank()) {
                        // LiteFlow 执行失败或内容为空，抛出异常触发重试
                        throw new IllegalStateException(
                                "follow-up LiteFlow result is empty"
                        );
                    }
                    return context.getContent();
                }
        );

        // 检查 fence 是否通过
        // 如果 !acceptFence，说明这个结果已经"过期"了
        // （比如另一个线程已经成功写入了更新的结果）
        if (!singleflight.acceptFence(
                key,
                result.token()
        )) {
            throw new IllegalStateException(
                    "stale follow-up result rejected"
            );
        }

        return result.value();
    }
}
