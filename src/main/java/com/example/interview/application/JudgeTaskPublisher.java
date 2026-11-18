package com.example.interview.application;

import com.example.interview.config.RabbitConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 评分任务投递器：把消息放进主队列 / 延迟重试队列 / 死信队列。
 *
 * <h3>为什么这个类只负责「发」，不再负责「取」</h3>
 * 原来是「发布 + 消费 + 重试 + 死信」挤在一个类里（pullOnce / consumeOnce / retryOrDeadLetter），
 * 因为 Redis List 没有消费端语义，只能自己写循环去拉。换成 RabbitMQ 之后，
 * 消费由 {@code @RabbitListener} 驱动、ack 由 Channel 控制，这些都不再需要手写。
 * 职责一分开，「投递」和「消费」各自的失败面就独立了。
 *
 * <h3>publisher confirm 干什么</h3>
 * {@code rabbitTemplate.convertAndSend} 返回得很快，但它只代表「方法没抛异常」，
 * 不代表消息真的进了 broker 的队列。发布那一刻 broker 挂了、交换机路由不到队列，
 * 消息就静默丢了——而库里那条答案还停在「待评分」，没有任何人会来救它。
 * 开启 correlated confirm 之后，「发布成功」变成一个可确认的事实，没确认就打日志告警。
 */
@Service
public class JudgeTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(JudgeTaskPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public JudgeTaskPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    /**
     * 注册发布确认回调：只记日志，不在这里重发。
     *
     * <p>为什么不自动重发：重发需要知道「发的是哪条任务」，而回调里只有 correlationData。
     * 更稳的兜底是「库里那条答案还是待评分」——真有消息丢了，按状态扫一遍就能补，
     * 比在回调里做一次不确定是否成功重试更可靠。
     */
    @PostConstruct
    void registerConfirmCallback() {
        rabbit.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("评分/报告消息未被 broker 确认，可能已丢失: id={} cause={}",
                        correlationData == null ? "null" : correlationData.getId(), cause);
            }
        });
        rabbit.setReturnsCallback(returned ->
                log.error("消息无法路由到队列: exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    /**
     * 提交答案后投递评分任务。
     *
     * <p>返回即代表「已投递」，不代表「已评分」——接口立刻返回给用户，
     * 真正的 AI 调用在消费者那边跑。
     */
    public void publish(UUID sessionId, UUID questionId, String answer, boolean followUp) {
        send(RabbitConfig.JUDGE_QUEUE, new JudgeTask(sessionId, questionId, answer, followUp, 0));
    }

    /** 可重试失败：投延迟队列，TTL 到期后经死信交换机自动回主队列 */
    public void retryLater(JudgeTask task) {
        send(RabbitConfig.JUDGE_RETRY_QUEUE, task);
    }

    /** 重试超限 / 不可重试：进死信，原样留现场 */
    public void deadLetter(JudgeTask task) {
        send(RabbitConfig.JUDGE_DEAD_QUEUE, task);
    }

    private void send(String routingKey, Object task) {
        try {
            String payload = mapper.writeValueAsString(task);
            rabbit.convertAndSend(routingKey, payload, message -> {
                // persistent：broker 重启后消息不丢。默认就是 persistent，
                // 显式写出来是为了让「这条消息必须活过重启」在代码里可见。
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            log.info("已投递 queue={} payload={}", routingKey, payload);
        } catch (JsonProcessingException e) {
            // 载荷是 record + 基本类型，序列化失败属于程序错误，直接抛
            throw new IllegalStateException("无法序列化评分任务", e);
        }
    }
}
