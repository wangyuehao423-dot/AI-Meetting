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
 * 追问消费者：RabbitMQ 手动 ack + AI 调用超时 + 延迟重试 + 死信。
 *
 * <h3>主链路</h3>
 * 收到消息 → 调追问生成服务（决策 → 跑 LiteFlow 链 → 幂等落库 → 推进状态）→ {@code basic.ack}。
 * ack 在业务成功之后：中途崩溃 → 消息没确认 → broker 自动 requeue 给其他消费者。
 *
 * <h3>为什么还要单独加超时</h3>
 * 消费者活着、消息握在手里没 ack 时，broker 不会重投。追问一次要调两次模型
 * （先决定要不要追问，再生成追问正文），上游抖动时可能长时间不返回，
 * 这条消息会把一个监听线程占死。所以用独立线程池执行、主线程等固定秒数，
 * 超时就按「可重试失败」处理，投延迟重试队列并 ack 原消息。
 *
 * <h3>重投的幂等由谁保证</h3>
 * 追问落库走 persistFollowUp 节点的幂等写入（同一题同一轮不会写出两条追问），
 * 所以重投最多浪费一次模型调用，不会产生重复追问。
 */
@Component
public class FollowUpTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(FollowUpTaskConsumer.class);

    /** 重试上限：3 次。再多基本可以断定不是抖动，而是数据或上游配置坏了 */
    private static final int MAX_RETRY = 3;

    private final FollowUpGenerationService generation;
    private final FollowUpTaskPublisher publisher;
    private final ObjectMapper mapper;
    private final int timeoutSeconds;

    /**
     * 追问专用线程池：只用来给「等 AI」加超时。
     *
     * <p>为什么不能直接在监听线程里调生成服务：一旦 AI 挂住，监听线程就永远卡在那，
     * prefetch 会把消息全压在这一个消费者上，其他实例分不到活，整个队列等于停摆。
     */
    private final ExecutorService followUpExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "follow-up-ai-");
        t.setDaemon(true);
        return t;
    });

    public FollowUpTaskConsumer(FollowUpGenerationService generation,
                                FollowUpTaskPublisher publisher,
                                ObjectMapper mapper,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${app.interview.follow-up-timeout-seconds:60}") int timeoutSeconds) {
        this.generation = generation;
        this.publisher = publisher;
        this.mapper = mapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    @PreDestroy
    void shutdown() {
        followUpExecutor.shutdownNow();
    }

    @RabbitListener(queues = RabbitConfig.FOLLOW_UP_QUEUE)
    public void onFollowUp(String payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        FollowUpTask task = null;
        try {
            task = mapper.readValue(payload, FollowUpTask.class);
            generateWithTimeout(task);
            // 追问落库、状态推进都完成了，才确认消费
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("追问任务失败，转延迟重试: {}", payload, e);
            retryOrDead(task, channel, tag);
        }
    }

    private void generateWithTimeout(FollowUpTask task) {
        FollowUpTask finalTask = task;
        Future<?> future = followUpExecutor.submit(() -> generation.generate(finalTask));
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("追问生成超过 " + timeoutSeconds + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("追问生成被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("追问生成失败", cause);
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
    private void retryOrDead(FollowUpTask task, Channel channel, long tag) throws IOException {
        if (task == null) {
            // 消息体本身反序列化不了：重投也还是解析不了，直接 ack 掉并记日志
            channel.basicAck(tag, false);
            return;
        }
        if (task.retryCount() >= MAX_RETRY) {
            publisher.deadLetter(task);
        } else {
            publisher.retryLater(new FollowUpTask(
                    task.sessionId(), task.questionId(), task.score(), task.retryCount() + 1));
        }
        channel.basicAck(tag, false);
    }
}
