package com.example.interview.application;

import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.infrastructure.lock.QuestionLockManager;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.springframework.stereotype.Service;

@Service 
public class AnswerCommandService {
    private final InterviewSessionStateMachine machine;

    private final AnswerRepository answers;

    private final SessionSnapshotRepository snapshots;

    private final QuestionLockManager locks;

    private final JudgeTaskPublisher publisher;

    // 从这里开始是新增的：预生成服务。为什么注入到"提交答案"这一环：
    // 只有这里能确定"答案已落库、快照已 CAS 成功"的那一刻；早一步触发会预生成一道
    // 对不上成功答题的题，题号也跟着错位。
    private final NextQuestionPreGeneratorService preGenerator;
    // 新增结束

    public AnswerCommandService(
        InterviewSessionStateMachine machine,
        AnswerRepository answers,
        SessionSnapshotRepository snapshots,
        QuestionLockManager locks,
        JudgeTaskPublisher publisher,
            // 从这里开始是新增的：构造器末尾追加一个参数，Spring 按类型自动注入。
            // 注意原有的五个参数顺序、缩进都不动，只在末尾加这个逗号与参数。
            NextQuestionPreGeneratorService preGenerator
            // 新增结束
) {
    this.machine = machine;
    this.answers = answers;
    this.snapshots = snapshots;
    this.locks = locks;
    this.publisher = publisher;
    this.preGenerator = preGenerator;   // 新增：落到 final 字段，服务不可变
}

    public void submit(AnswerCommand command)
    {
        var lockKey = "interview:answer:" + command.sessionId() + ":" + command.questionId();
        try(var lock = locks.tryLock(lockKey))
        {
            if(!lock.acquired())
            {
                throw new IllegalStateException("answer is being processed");
            }
            // 从这里开始是新增的：追问作答早返回分支。
            // 为什么必须放在 machine.advance 之前：追问不作为新题插入 interview_question，
            // 前端提交的 questionId 仍是原题 ID。若追问作答也走 ANSWER_SUBMITTED 推进状态机，
            // 同一道题会被推进两次、被算成两道题，题序与进度整体错乱。所以追问作答在这里就返回，
            // 只做"答案落库 + 投递追问评分任务"，既不推进主线状态机，也不触发下一题预生成
            // （追问之后不再预生成新题，避免题号被追问带偏）。
            // 仍然保留锁与 insertIfAbsent：锁防止同一道题的追问被并发重复处理，
            // insertIfAbsent 靠唯一键 (question_id, is_follow_up) 兜底幂等。
            if (command.followUp())
            {
                answers.insertIfAbsent(command);
                // 末位传 true：评分队列据此把分数写回 is_follow_up=1 那一行，且不推进主线。
                publisher.publish(
                        command.sessionId(),
                        command.questionId(),
                        command.answer(),
                        true
                );
                return;
            }
            // 新增结束
            var oldSnapshot = snapshots.load(command.sessionId());
            var result = machine.advance(oldSnapshot, InterviewEvent.ANSWER_SUBMITTED, command.requestId());
            if(!result.changed())
            {
                return;
            }
            answers.insertIfAbsent(command);
            if(!snapshots.compareAndSet(oldSnapshot, result.snapshot()))
            {
                throw new IllegalStateException(
                    "snapshot version conflict"
                );
            }
            publisher.publish(
                command.sessionId(),
                command.questionId(),
                command.answer(),
                // 从这里开始是修改的：初次作答路径末位传 false，评分写回 is_follow_up=0 那一行并推进主线。
                false
                // 修改结束
        );
            // 从这里开始是新增的：触发下一题预生成。
            // 为什么写在 publish 之后：到这一步答案确实已落库、快照确实已推进，题号才有意义。
            // 为什么必须还在锁里面（也就是那个 try(var lock = ...) 的花括号内）：
            // 题号按"当前题序号 + 1"算，锁一放开，同一会话的下一题可能立刻被另一个请求接手，
            // 两边算出同一个题号。
            int nextSequenceNo = answers.findSequenceNo(
                    command.questionId()
            ) + 1;
            // @Async 方法，调完立即返回：这里不等待 AI，用户提交答案的响应不会因此变慢。
            preGenerator.preGenerate(
                    command.sessionId(),
                    nextSequenceNo
            );
            // 新增结束
        }
    }
}
