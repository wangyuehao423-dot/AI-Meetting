package com.example.interview.domain;


import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public class InterviewSessionStateMachine
{
    private final Map<
            InterviewState,
            Map<InterviewEvent, InterviewState>
            > transitions = new EnumMap<>(
            InterviewState.class
    );
    // 构造状态机并注册整个面试生命周期的合法转移。
    public InterviewSessionStateMachine() {
        // 构造时一次性注册所有合法的“当前状态 + 事件 -> 下一状态”。
        register(
                InterviewState.INIT,
                InterviewEvent.START,
                InterviewState.MATCHING
        );
        register(
                InterviewState.MATCHING,
                InterviewEvent.POSITION_MATCHED,
                InterviewState.QUESTIONING
        );
        register(
                InterviewState.QUESTIONING,
                InterviewEvent.QUESTION_READY,
                InterviewState.ANSWERING
        );
        // 从这里开始是新增的：容许"题目已展示但状态仍停在 QUESTIONING"时提交答案。
        // 为什么必须加这一条：出题流程里 QUESTION_READY（QUESTIONING→ANSWERING）那一步的
        // CAS 推进失败会被静默忽略（见 InterviewPreparationAsyncService.advance 的调用处），
        // 于是前端已经把题目展示给用户了，会话快照却还停在 QUESTIONING。
        // 此时用户提交答案触发 ANSWER_SUBMITTED，原状态机里 QUESTIONING + ANSWER_SUBMITTED
        // 没有转移规则，直接抛 invalid transition，前端就收到 500。
        // 题目既然已经展示，用户的作答就应当被接受并正常进入评分（JUDGING），
        // 评分后续的 JUDGE_DONE→ASKING 流转不受影响。
        register(
                InterviewState.QUESTIONING,
                InterviewEvent.ANSWER_SUBMITTED,
                InterviewState.JUDGING
        );
        // 新增结束
        register(
                InterviewState.ANSWERING,
                InterviewEvent.ANSWER_SUBMITTED,
                InterviewState.JUDGING
        );
        register(
                InterviewState.JUDGING,
                InterviewEvent.JUDGE_DONE,
                InterviewState.ASKING
        );
        register(
                InterviewState.ASKING,
                InterviewEvent.ASK_COMPLETED,
                InterviewState.QUESTIONING
        );
        register(
                InterviewState.ANSWERING,
                InterviewEvent.TIMEOUT,
                InterviewState.JUDGING
        );

        for (var state : InterviewState.values()) {
            // END 是全局事件，任意状态都允许结束。
            register(
                    state,
                    InterviewEvent.END,
                    InterviewState.ENDED
            );
        }
    }
    public record TransitionResult(
            // 状态机处理后的新快照；幂等请求时仍然返回旧快照。
            InterviewSessionSnapshot snapshot,
            // true 表示发生了状态变化，false 表示重复请求被忽略。
            boolean changed
    ) {
        /**
         * 为重复请求创建“不变更状态”的结果，调用方仍可拿到原快照。
         */
        static TransitionResult idempotent(InterviewSessionSnapshot snapshot) {
            return new TransitionResult(snapshot, false);
        }

        /**
         * 为成功推进状态创建“已变更”的结果对象。
         */
        static TransitionResult changed(InterviewSessionSnapshot snapshot) {
            return new TransitionResult(snapshot, true);
        }
    }
    private void register(InterviewState from,
                          InterviewEvent event,
                          InterviewState to)
    {
        // 一个状态可能对应多个事件，因此使用两级 Map 保存转移表。
        transitions
                .computeIfAbsent(
                        from,
                        // 没有当前状态的子 Map 时才创建一次。
                        ignored -> new EnumMap<>(
                                InterviewEvent.class
                        )
                )
                .put(event, to);
    }
    public TransitionResult advance(InterviewSessionSnapshot snapshot,
                                     InterviewEvent event,
                                     String requestId)
    {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(event);
        Objects.requireNonNull(requestId);

        if(snapshot.hasProcessed(requestId))
        {
            return TransitionResult.idempotent(snapshot);
        }
        //查状态转换是否合法
        var eventMap = transitions.getOrDefault(snapshot.state(),Map.of());
        var nextState = eventMap.get(event);

        // 找不到转移规则说明调用方违反了面试生命周期。
        if (nextState == null) {
            throw new IllegalStateException(
                    "invalid transition: "
                            + snapshot.state()
                            + " + "
                            + event
            );
        }

        if (nextState == snapshot.state()) {
            // 目标状态与当前状态相同（例如已经 ENDED 的会话再次收到 END），
            // 说明这是一次重复推进，返回"未变更"结果，保证终态幂等。
            return TransitionResult.idempotent(snapshot);
        }

        return TransitionResult.changed(
                snapshot.moveTo(
                        nextState,
                        requestId
                )
        );
    }
}
