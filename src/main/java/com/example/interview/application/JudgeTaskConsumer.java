package com.example.interview.application;

import com.example.interview.config.RabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 评分消费者：RabbitMQ 手动 ack + AI 调用超时 + 延迟重试 + 死信。
 *
 * <h3>主链路</h3>
 * 收到消息 → 调评分工作流（AI 评分 + 落库 + 推进状态 + 投追问）→ {@code basic.ack}。
 * <b>ack 在业务成功之后</b>：第 ③ 步写完分数、第 ④ 步才 ack。中间崩溃 → 消息没确认 →
 * RabbitMQ 自动把它 requeue 给其他消费者。这就是「评分不会因为一台机器挂掉而丢」的全部秘密。
 *
 * <h3>两种「停住」，ack 都兜不住，必须自己处理</h3>
 * <ol>
 *   <li><b>AI 长尾卡住消费者</b>：消费者活着、消息还握在手里没 ack，broker 不会重投。
 *       所以调 AI 必须设超时（30 秒），超时主动按「可重试失败」处理，投延迟重试队列。</li>
 *   <li><b>发布时 broker 恰好挂了</b>：消息根本没进队列，没有任何消费者会拿到它。
 *       这个缺口由 publisher confirm 兜（见 JudgeTaskPublisher）。</li>
 * </ol>
 *
 * <h3>为什么超时用 Future 包一层，而不是只改 HTTP 超时</h3>
 * 项目里 AI 客户端的 read timeout 是 180 秒（给报告这种长生成留时间）。
 * 如果把全局超时改成 30 秒，报告生成会被腰斩。所以超时控制放在「评分这一条链路」上：
 * 用独立的线程池执行评分，主线程等 30 秒，超时就放弃等待并走重试。
 */
@Component
public class JudgeTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(JudgeTaskConsumer.class);

    /**
     * 评分超时：正常 3~10 秒，长尾几十秒。
     * 原来是写死的 30 秒，实测上游抖动时单个评分经常 25~30 秒才回，正好卡在超时线上：
     * 一超时就转延迟重试，重试又超时，用户在界面上看到的是一次取题要等一两分钟。
     * 现在默认 60 秒（可用 app.interview.judge-timeout-seconds 覆盖）：
     * 慢但能成功的调用直接让它成功，比"超时→重试→再超时"更省时间。
     * 消息最长只被握 60 秒，手动 ack + 延迟重试那条通路不变。
     */
    private final int aiTimeoutSeconds;

    /** 重试上限：3 次。再多基本可以断定不是抖动，而是数据或上游配置坏了 */
    private static final int MAX_RETRY = 3;

    private final InterviewWorkflowService workflow;
    private final JudgeTaskPublisher publisher;
    private final ObjectMapper mapper;

    /**
     * 评分专用线程池：只用来给「等 AI」加超时。
     *
     * <p>为什么不能直接在监听线程里调 workflow：一旦 AI 挂住，监听线程就永远卡在那，
     * prefetch 会把消息全压在这一个消费者上，其他实例分不到活，整个队列等于停摆。
     */
    private final ExecutorService judgeExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "judge-ai-");
        t.setDaemon(true);
        return t;
    });

    public JudgeTaskConsumer(InterviewWorkflowService workflow,
                             JudgeTaskPublisher publisher,
                             ObjectMapper mapper,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${app.interview.judge-timeout-seconds:60}") int aiTimeoutSeconds) {
        this.workflow = workflow;
        this.publisher = publisher;
        this.mapper = mapper;
        this.aiTimeoutSeconds = aiTimeoutSeconds;
    }

    @PreDestroy
    void shutdown() {
        judgeExecutor.shutdownNow();
    }

    @RabbitListener(queues = RabbitConfig.JUDGE_QUEUE)
    public void onJudge(String payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        JudgeTask task = null;
        try {
            task = mapper.readValue(payload, JudgeTask.class);
            judgeWithTimeout(task);
            // 分数落库、状态推进都完成了，才确认消费
            channel.basicAck(tag, false);
        } catch (AiTimeoutException e) {
            log.warn("评分超时（{}s），转延迟重试: {}", aiTimeoutSeconds, payload);
            retryOrDead(task, channel, tag);
        } catch (Exception e) {
            log.error("评分失败，转延迟重试: {}", payload, e);
            retryOrDead(task, channel, tag);
        }
    }

    private void judgeWithTimeout(JudgeTask task) {
        Future<?> future = judgeExecutor.submit(() ->
                workflow.judge(task.sessionId(), task.questionId(), task.answer(), task.followUp()));
        try {
            future.get(aiTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // cancel(true) 只是「请求中断」：底层 HTTP 调用可能还会跑一会儿才结束，
            // 但它已经不影响这条消息的命运了——消息会被重投，靠的是答案表的唯一键
            // (question_id, is_follow_up) 保证重投幂等，不会出现同题双评。
            future.cancel(true);
            throw new AiTimeoutException("评分超过 " + aiTimeoutSeconds + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("评分被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("评分执行失败", cause);
        }
    }

    /**
     * 重试或进死信。
     *
     * <p>为什么重试投「延迟队列」而不是 {@code basicNack(requeue=true)}：
     * requeue 会立刻把消息放回队首，一个正在限流的 AI 接口会被连续打三次、三次全败进死信，
     * 重试等于没重试。延迟队列让每次重试之间隔 10 秒，给下游留恢复窗口。
     *
     * <p>无论走哪条路都要 ack 原消息：不 ack 它会一直躺在 unacked 里，
     * 直到连接断开被重投，等于又绕回了「无限热重试」。
     */
    private void retryOrDead(JudgeTask task, Channel channel, long tag) throws IOException {
        if (task == null) {
            // 消息体本身反序列化不了：重投也还是解析不了，直接 ack 掉并记日志
            channel.basicAck(tag, false);
            return;
        }
        if (task.retryCount() >= MAX_RETRY) {
            publisher.deadLetter(task);
        } else {
            publisher.retryLater(new JudgeTask(
                    task.sessionId(), task.questionId(), task.answer(),
                    task.followUp(), task.retryCount() + 1));
        }
        channel.basicAck(tag, false);
    }

    /** 评分超时标记：和普通异常区分开，日志与重试语义更清楚 */
    static class AiTimeoutException extends RuntimeException {
        AiTimeoutException(String message) {
            super(message);
        }
    }
}
