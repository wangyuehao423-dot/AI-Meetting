package com.example.interview.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息队列拓扑：评分、报告、追问三条线，共用同一套「主队列 + 延迟重试队列 + 死信队列」结构。
 *
 * <h3>为什么三类异步任务全部走 RabbitMQ</h3>
 * 这三件事要的是同一组东西——消费确认、失败重试、死信、多消费者负载均衡、持久化、积压监控。
 * 一套 broker 参数就全给了，运维只需要盯一个 RabbitMQ 控制台。
 * 如果再有一类任务去手写 Redis List（三 key 搬运 + 心跳 + 补偿扫库），
 * 等于同一个系统并存两套队列语义：面试官第一个问题就是「为什么这里不用 MQ」，
 * 而答案是「没有理由」——所以追问也统一迁到 MQ 上。
 *
 * <h3>为什么重试走「延迟队列」而不是 nack(requeue=true)</h3>
 * requeue 会让消息立刻回到队首，形成热循环：一个正在限流的 AI 接口会被瞬间打满三次，
 * 三次全失败直接进死信，重试等于没重试。延迟队列把重试摊开（TTL 10 秒后经 DLX 回主队列），
 * 给下游留恢复窗口。
 */
@Configuration
public class RabbitConfig {

    /* ---------------- 评分 ---------------- */

    /** 评分主队列：提交答案后投递，消费者在这里取 */
    public static final String JUDGE_QUEUE = "interview.judge.queue";

    /** 延迟重试队列：TTL 10 秒，到期经 DLX 自动回主队列 */
    public static final String JUDGE_RETRY_QUEUE = "interview.judge.retry.queue";

    /** 死信队列：重试超限 / 不可重试失败，原样留现场给运维 */
    public static final String JUDGE_DEAD_QUEUE = "interview.judge.dead.queue";

    /* ---------------- 报告 ---------------- */

    /** 报告主队列：和评分统一走队列，少一种机制 */
    public static final String REPORT_QUEUE = "interview.report.queue";

    /** 报告延迟重试队列：TTL 10 秒，到期经 DLX 自动回主队列 */
    public static final String REPORT_RETRY_QUEUE = "interview.report.retry.queue";

    /** 报告死信队列：重试超限进这里，前端置 FAILED 后可点「重新生成」再投一次 */
    public static final String REPORT_DEAD_QUEUE = "interview.report.dead.queue";

    /* ---------------- 追问 ---------------- */

    /** 追问主队列：评分落库后投递，消费者在这里取 */
    public static final String FOLLOW_UP_QUEUE = "interview.follow-up.queue";

    /** 追问延迟重试队列：TTL 10 秒，到期经 DLX 自动回主队列 */
    public static final String FOLLOW_UP_RETRY_QUEUE = "interview.follow-up.retry.queue";

    /** 追问死信队列：重试超限 / 不可重试失败，原样留现场 */
    public static final String FOLLOW_UP_DEAD_QUEUE = "interview.follow-up.dead.queue";

    /** 重试间隔 10 秒 */
    private static final int RETRY_TTL_MS = 10_000;

    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable(JUDGE_QUEUE).build();
    }

    @Bean
    public Queue judgeRetryQueue() {
        return QueueBuilder.durable(JUDGE_RETRY_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                // 死信交换机留空 = 默认交换机，routingKey 写目标队列名，到期直接回主队列
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", JUDGE_QUEUE)
                .build();
    }

    @Bean
    public Queue judgeDeadQueue() {
        return QueueBuilder.durable(JUDGE_DEAD_QUEUE).build();
    }

    @Bean
    public Queue reportQueue() {
        return QueueBuilder.durable(REPORT_QUEUE).build();
    }

    @Bean
    public Queue reportRetryQueue() {
        return QueueBuilder.durable(REPORT_RETRY_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", REPORT_QUEUE)
                .build();
    }

    @Bean
    public Queue reportDeadQueue() {
        return QueueBuilder.durable(REPORT_DEAD_QUEUE).build();
    }

    @Bean
    public Queue followUpQueue() {
        return QueueBuilder.durable(FOLLOW_UP_QUEUE).build();
    }

    @Bean
    public Queue followUpRetryQueue() {
        return QueueBuilder.durable(FOLLOW_UP_RETRY_QUEUE)
                .withArgument("x-message-ttl", RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", FOLLOW_UP_QUEUE)
                .build();
    }

    @Bean
    public Queue followUpDeadQueue() {
        return QueueBuilder.durable(FOLLOW_UP_DEAD_QUEUE).build();
    }
}
