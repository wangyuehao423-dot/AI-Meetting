package com.example.interview.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class JudgeTaskPublisher {
    private static final String QUEUE = "interview:task:judge";

    private static final String PROCESSING = "interview:task:judge:processing";

    private static final String DEAD = "interview:task:judge:dead";

    private static final int MAX_RETRY = 3;

    // 评分工作流：真正执行"AI 评分 + 落库 + 推进状态 + 触发追问"的那一环。
    // 全类只声明这一次，构造器注入一次，避免重复字段导致编译报错。
    private final InterviewWorkflowService workflow;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public JudgeTaskPublisher(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            InterviewWorkflowService workflow
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.workflow = workflow;
    }

    public void publish(UUID sessionId, UUID questionId, String answer,
                        // 从这里开始是修改的：新增 followUp 参数。
                        // 追问作答与初次作答共用同一条评分队列，队列载荷必须带上这个标记，
                        // 否则消费者无法区分"该写哪一行、该不该推进状态机"。
                        boolean followUp)
                        // 修改结束
    {
        try
        {
            // 从这里开始是修改的：构造 JudgeTask 时带上 followUp（位置在 retryCount 之前，末位仍传 0 表示首次投递）。
            redis.opsForList().rightPush(QUEUE, mapper.writeValueAsString(new JudgeTask(sessionId, questionId, answer, followUp, 0)));
            // 修改结束

        }
        catch(JsonProcessingException e)
        {
            // 什么情况下会走到这里：ObjectMapper 无法序列化 JudgeTask。
            // 实际上 JudgeTask 是一个 record，字段都是基本类型+String+UUID，
            // 几乎不可能序列化失败。
            // 但是 Jackson 声明抛出 JsonProcessingException，我们必须处理。
            // 这里包装为 IllegalStateException（非受检异常），
            // 让调用方不需要显式 try-catch，简化 controller 层的代码。
            throw new IllegalStateException(
                    "无法序列化评分任务: sessionId=" + sessionId
                            + ", questionId=" + questionId,
                    e
            );
        }
    }

    @Async("aiScoringTaskExecutor")
    public void consumeOnce()
    {
        String raw = redis.opsForList().rightPopAndLeftPush(QUEUE, PROCESSING,Duration.ofMillis(200));
        if(raw == null)
        {
            return;
        }
        JudgeTask task = null;
        try
        {
            task = mapper.readValue(raw, JudgeTask.class);
            // 从这里开始是修改的：把队列载荷里的 followUp 透传给工作流，追问作答才不会推进主线。
            workflow.judge(task.sessionId(), task.questionId(), task.answer(), task.followUp());
            // 修改结束
            redis.opsForList().remove(PROCESSING,1,raw);
        }
        catch(Exception e)
        {
            retryOrDeadLetter(raw,task);
        }
    }

    /**
     * 同步拉取并处理一条评分任务。
     *
     * <p>判定依据是队列搬运是否返回 null——返回 null 就说明待处理队列此刻没有任务，
     * 本轮无事可做。之所以在 {@code consumeOnce()} 之外再开一个方法：{@code consumeOnce()}
     * 上有 {@code @Async}，调用后会立刻返回，不能用来"循环拉取"；本方法保持同步语义，
     * 处理完才返回，正好放进 {@code JudgeTaskConsumerLoop} 的定时循环里。
     *
     * @return 本次是否消费到任务：true 表示确实拿到并处理了一条，false 表示队列已空
     */
    public boolean pullOnce()
    {
        // 把队首元素从 QUEUE 原子地搬到 PROCESSING：中间没有"取出来了但还没登记"的窗口，
        // 消费者此刻崩溃任务也还留在处理中队列里，补偿服务才能查得到并重投。
        // 从左边取是因为 publish 用的是 rightPush——右侧进、左侧出，先到先处理。
        // 这里是非阻塞版本：拿不到就返回 null，由外层 @Scheduled 反复调用，不会挂起线程。
        String raw = redis.opsForList().move(QUEUE, Direction.LEFT, PROCESSING, Direction.LEFT);
        // 返回 null 说明 QUEUE 已经空了，本轮没有任务可处理。
        if(raw == null)
        {
            return false;
        }
        JudgeTask task = null;
        try
        {
            // 反序列化后立刻执行评分工作流：AI 评分 → 落库 → 推进状态 → 触发追问。
            task = mapper.readValue(raw, JudgeTask.class);
            // 从这里开始是修改的：pullOnce 同样要把 followUp 透传下去（追问任务重投后仍保持追问语义）。
            workflow.judge(task.sessionId(), task.questionId(), task.answer(), task.followUp());
            // 修改结束
            // 评分成功，把任务从处理中队列摘掉，表示这条已经处理完毕。
            redis.opsForList().remove(PROCESSING,1,raw);
        }
        catch(Exception e)
        {
            // 任何环节失败（反序列化 / AI 评分 / 落库）都交给重投或死信，
            // 避免任务永远留在处理中队列里没人管。
            retryOrDeadLetter(raw,task);
        }
        return true;
    }
    public void retryOrDeadLetter(String raw, JudgeTask task)
    {
        if(task == null || task.retryCount() >= MAX_RETRY)
        {
            redis.opsForList().rightPush(DEAD, raw);
            redis.opsForList().remove(PROCESSING, 1, raw);
            return;
        }
        try
        {
            // 从这里开始是修改的：重投时原样保留 followUp，只递增 retryCount，
            // 保证追问任务重投后仍走"不推进主线"的追问分支。
            JudgeTask retry = new JudgeTask(task.sessionId(), task.questionId(), task.answer(), task.followUp(), task.retryCount() + 1);
            // 修改结束
            redis.opsForList().rightPush(QUEUE, mapper.writeValueAsString(retry));
            redis.opsForList().remove(PROCESSING, 1, raw);
        }
        catch(JsonProcessingException serializationError)
        {
            throw new IllegalStateException(
                    "无法序列化重试任务: sessionId="
                            + task.sessionId()
                            + ", questionId=" + task.questionId(),
                    serializationError
            );
        }
    }
}