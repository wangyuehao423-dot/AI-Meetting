package com.example.interview.application;

import com.example.interview.ai.FollowUpDecisionService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service 
public class InterviewWorkflowService {
    private final SessionSnapshotRepository snapshots;
    private final InterviewAiFacade ai;
    private final AnswerRepository answers;
    private final InterviewSessionStateMachine machine;
    // 追问决策服务：只负责"分数 -> 追问策略（链名/等级/目标）"的映射。
    private final FollowUpDecisionService followUps;
    // 从这里开始是新增的：追问任务发布器。原来是 FollowUpGenerationService followUpGeneration，
    // 现在换成 FollowUpTaskPublisher —— judge 只需要"把任务发出去"，
    // 一旦在这里直接调生成服务，就又变回同步执行，异步化白做。
    private final FollowUpTaskPublisher followUpPublisher;
    // 新增结束
    // 从这里开始是新增的：下一题预生成服务，评分失败降级推进时复用。
    private final NextQuestionPreGeneratorService preGenerator;
    // 日志对象：评分失败降级、追问失败都要留痕——异步线程没有调用方接住异常。
    private static final Logger log =
            LoggerFactory.getLogger(InterviewWorkflowService.class);
    // 新增结束

    public InterviewWorkflowService(
            SessionSnapshotRepository snapshots,
            InterviewAiFacade ai,
            AnswerRepository answers,
            InterviewSessionStateMachine machine,
            FollowUpDecisionService followUps,
            // 从这里开始是新增的：末位参数由 FollowUpTaskPublisher 改为 FollowUpTaskPublisher
            FollowUpTaskPublisher followUpPublisher,
            // 从这里开始是新增的：追加预生成服务，Spring 按类型注入。
            NextQuestionPreGeneratorService preGenerator
            // 新增结束
    ) {
        this.snapshots = snapshots;
        this.ai = ai;
        this.answers = answers;
        this.machine = machine;
        this.followUps = followUps;
        this.followUpPublisher = followUpPublisher;
        // 新增：落到 final 字段，服务不可变。
        this.preGenerator = preGenerator;
    }
    public record WorkflowResult(
            double score,
            // 追问策略链名，由 FollowUpDecisionService 根据分数选出：
            // <60 基础追问、60-79 常规追问、>=80 深度追问。
            // 追问模块创建前这里只是 boolean 占位，现在已经是真实的链名。
            String chain,
            com.example.interview.domain.InterviewState state,
            String feedback,
            boolean stale,
            long fencingToken,
            // 末位：原来是 boolean replayed，改为 boolean hasFollowUp。
            // 追问已改成异步，judge 返回的这一刻追问必然还没生成，所以两个分支都传 false。
            boolean hasFollowUp
    ) {
    }

    /**
     * 评分工作流：把"AI 评分 -> 评分落库 -> 推进状态 -> 触发追问"串成一条链路。
     *
     * <p>为什么要做成一条工作流而不是散落在多个类里：
     * 评分不只是一次 AI 调用，它还要写库、推进状态机、决定要不要追问、
     * 以及处理"同一个评分任务被重复投递"的情况。这些步骤必须按固定顺序执行，
     * 放在一个方法里才能保证顺序和幂等语义清晰可读。</p>
     *
     * <p>幂等与回放：如果这道题在 answer 表里已经有分数（persisted != null），
     * 说明这是一次重复投递的评分任务，直接回放已保存的结果（replayed=true），
     * 不再重复写库、不再推进状态、也不再触发追问——
     * 否则同一道题会被追问两次，面试主线会被推进两格。</p>
     *
     * <p>状态流转：JUDGING --JUDGE_DONE--> ASKING，
     * 再由追问生成服务完成 ASKING --ASK_COMPLETED--> QUESTIONING，回到出题/答题循环。</p>
     *
     * <p>追问作答（followUp=true）的处理：追问内容存在 interview_follow_up 表，不作为新题插入
     * interview_question，前端提交的 questionId 仍是原题 ID。因此这里必须走一个独立的早返回分支：
     * 只做「AI 评分 → 落库 → 返回结果」，绝不推进主线状态机、不 CAS 写回快照、不发布追问任务。
     * 原因：主线状态机（ANSWER_SUBMITTED / JUDGE_DONE）表达的是"原题的一次完整作答"，
     * 若追问作答也推一次，同一道题会被算成两道题，题序与进度整体错乱；而且追问的追问不生成，
     * 所以追问分支也不会再往追问队列投任务。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 本次评分的题目 ID
     * @param answer     候选人答案文本
     * @param followUp   是否追问作答：false=初次作答、true=追问作答
     * @return 本次评分结果；replayed=true 表示这是回放的已保存结果
     */
    public WorkflowResult judge(UUID sessionId,
                                UUID questionId,
                                String answer,
                                // 从这里开始是修改的：新增 followUp 参数，用于区分初次作答与追问作答。
                                boolean followUp
                                // 修改结束
    )
    {
        // 从这里开始是新增的：追问作答早返回分支。
        // 为什么必须在最前面判掉：一旦往下走到 machine.advance(ANSWER_SUBMITTED/JUDGE_DONE)，
        // 同一道题就会被推进两次，题序错乱且会被计入两道题。所以追问作答在这里就返回，
        // 完全不碰主线状态机、快照 CAS 和追问任务发布。
        if (followUp)
        {
            // 追问评分复用同一条 judge 链路，只是提示词里标明"这是追问回答"（见 InterviewAiFacade.judge）。
            // 从这里开始是修改的：追问作答也不需要给前端无限等待，AI 瞬时失败就给兜底分数继续。
            JudgeResult score;
            try {
                score = ai.judge(sessionId, questionId, answer, true);
            } catch (Exception e) {
                log.error("judge follow-up failed, fallback score, sessionId={}", sessionId, e);
                score = fallbackScore();
            }
            // 修改结束
            // 幂等回放：追问评分任务可能被重复投递（消费者崩溃后补偿重投），
            // 若 is_follow_up=1 那一行已有分数，直接回放，避免重复调用 AI、重复写库。
            var persisted = answers.findJudge(sessionId, questionId, true);
            if (persisted != null)
            {
                return new WorkflowResult(
                        persisted.score(),
                        followUps.chooseChain(persisted.score().intValue()),
                        // 只读当前快照状态用于返回，不做任何 CAS 写回。
                        snapshots.load(sessionId).state(),
                        persisted.feedback(),
                        false,
                        persisted.fencingToken(),
                        false
                );
            }
            // 追问评分写回 is_follow_up=1 那一行，绝不覆盖初次评分。
            answers.recordJudge(sessionId, questionId, score, true);
            // 追问作答到此结束：不推进状态机、不写快照、不发布追问任务，直接返回本次评分。
            return new WorkflowResult(
                    score.score(),
                    followUps.chooseChain((int) score.score()),
                    snapshots.load(sessionId).state(),
                    score.feedback(),
                    score.stale(),
                    score.fencingToken(),
                    false
            );
        }
        // 新增结束

        var old = snapshots.load(sessionId);
        // 从这里开始是修改的：评分 AI 一次调用可能瞬时失败。失败绝不能把状态永远停在 JUDGING
        //（否则前端轮询下一题会无限转圈），所以这里捕获后给一个兜底评分，降级继续推进面试。
        JudgeResult score;
        boolean degraded = false;
        try {
            score = ai.judge(sessionId, questionId, answer, false);
        } catch (Exception e) {
            log.error("judge failed, degraded to continue, sessionId={}", sessionId, e);
            score = fallbackScore();
            degraded = true;
        }
        // 修改结束
        var persisted = answers.findJudge(sessionId, questionId, false);
        if(persisted != null)
        {
            // 回放分支：分数、反馈、围栏令牌都取数据库里已保存的那一份。
            // chain 用已保存的分数反推，保证回放结果和首次评分返回的追问链一致；
            // 这一条没有真正生成过追问，所以无需再调用追问生成服务。
            return new WorkflowResult(
                    persisted.score(),           // 已保存的分数
                    followUps.chooseChain(
                            persisted.score().intValue()
                    ),                           // 用同一份分数反推追问链名
                    snapshots.load(sessionId)    // 当前快照状态
                            .state(),
                    persisted.feedback(),        // 已保存的反馈
                    false,                       // 不是过期结果
                    persisted.fencingToken(),    // 已保存的令牌
                    // 末位字段已由 replayed 改为 hasFollowUp：异步化之后 judge 返回时追问必然还没生成，
                    // 所以这里固定给 false（"是否重放"不再由这个字段表达）。
                    false
            );
        }
        answers.recordJudge(
            sessionId,
            questionId,
            score,
            // 从这里开始是修改的：初次作答写回 is_follow_up=0 那一行，明确不覆盖追问评分。
            false
            // 修改结束
        );
        var moved = machine.advance(old, InterviewEvent.JUDGE_DONE, UUID.randomUUID().toString());
        if(moved.changed())
        {
            snapshots.compareAndSet(old, moved.snapshot());
        }

        // 从这里开始是新增的：评分降级（AI 失败）时不再生成追问，直接把 ASKING→QUESTIONING
        // 推进到位并预生成下一题，让前端能立刻取下一题而不是一直等在轮询里。
        if (degraded) {
            var askDone = machine.advance(
                    moved.snapshot(),
                    InterviewEvent.ASK_COMPLETED,
                    UUID.randomUUID().toString()
            );
            if (askDone.changed()) {
                snapshots.compareAndSet(
                        moved.snapshot(),
                        askDone.snapshot()
                );
            }
            preGenerator.preGenerate(
                    sessionId,
                    answers.findSequenceNo(questionId) + 1
            );
            return new WorkflowResult(
                    score.score(),
                    "followUpBasicChain",
                    askDone.changed()
                            ? askDone.snapshot().state()
                            : moved.snapshot().state(),
                    score.feedback(),
                    score.stale(),
                    score.fencingToken(),
                    false
            );
        }
        // 新增结束

        // 评分已落库、状态已推进到 ASKING，接下来按分数选出追问策略。
        // decide 返回完整的追问计划（链名 + 等级 + 追问目标），
        // 其中链名既用于返回给调用方，也用于驱动下游的 LiteFlow 追问链。
        var plan = followUps.decide((int) score.score());

        // 从这里开始是修改的：原来这里是同步调 followUpGeneration.generate(new FollowUpTask(...))，
        // 会当场跑完整条 LiteFlow 追问链才返回，评分队列吞吐被追问 AI 拖死。
        // 现在只把任务丢进 Redis 队列就返回，追问由独立消费者生成并落库。
        // 评分落库、状态推进之后才发布追问任务，任务只带三样东西：哪个会话、哪道题、多少分
        // （走哪条链由消费端的 decide 再算一次，任务体保持最小）。
        // 顺序不能反：先发任务的话，消费者可能比评分落库更快到达，链上就会读到"还没有分数"的上下文。
        followUpPublisher.publish(
                sessionId,
                questionId,
                score.score()
        );
        // 修改结束

        // 返回本次评分结果（replayed=false）。
        // 注意 state 取的是 JUDGE_DONE 之后、追问生成之前的快照状态（ASKING），
        // 它描述的是"评分这一步完成时的状态"，继续往下走由追问模块负责。
        return new WorkflowResult(
                score.score(),
                plan.chain(),
                moved.snapshot().state(),
                score.feedback(),
                score.stale(),
                score.fencingToken(),
                false
        );
    }

    // 从这里开始是新增的：评分 AI 瞬时失败时的兜底评分。
    // 用稳定的及格线下分值（50），反馈写清楚"已自动继续"，让用户知道这题没拿到点评但面试没断。
    private JudgeResult fallbackScore()
    {
        return new JudgeResult(
                50,
                "评分服务暂时繁忙，本条回答已自动进入下一题。",
                0L,
                false
        );
    }
    // 新增结束
}