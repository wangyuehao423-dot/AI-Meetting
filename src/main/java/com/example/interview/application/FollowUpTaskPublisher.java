package com.example.interview.application;

import com.example.interview.config.RabbitConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 追问任务投递器：把消息放进主队列 / 延迟重试队列 / 死信队列。
 *
 * <h3>为什么这个类只负责「发」</h3>
 * 消费由 {@link FollowUpTaskConsumer} 的 {@code @RabbitListener} 驱动、ack 由 Channel 控制，
 * 不需要手写拉取循环。职责一分开，「投递」和「消费」各自的失败面就独立了。
 *
 * <h3>publisher confirm</h3>
 * {@code convertAndSend} 正常返回只代表「方法没抛异常」，不代表消息真的进了 broker。
 * 发布那一刻 broker 挂了、路由不到队列，消息就静默丢了——而库里那道题的评分已经落好，
 * 用户会卡在「评分完成但追问不出现」。全局的 confirm 回调在
 * {@link JudgeTaskPublisher#registerConfirmCallback()} 里统一注册（共用同一个 RabbitTemplate），
 * 三条线共用一份确认逻辑。
 */
@Service
public class FollowUpTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(FollowUpTaskPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public FollowUpTaskPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    /**
     * 发布追问任务。
     *
     * <p>由 InterviewWorkflowService.judge 在评分落库、状态推进之后调用，只带三样东西：
     * 哪个会话、哪道题、多少分——走哪条链由消费端的 decide 再算一次，任务体保持最小。
     * 这里不是异步方法：send 是毫秒级操作，发布失败必须让调用方当场感知。
     */
    public void publish(UUID sessionId, UUID questionId, double score) {
        send(RabbitConfig.FOLLOW_UP_QUEUE, new FollowUpTask(sessionId, questionId, score, 0));
    }

    /** 可重试失败：投延迟队列，TTL 到期后经死信交换机自动回主队列 */
    public void retryLater(FollowUpTask task) {
        send(RabbitConfig.FOLLOW_UP_RETRY_QUEUE, task);
    }

    /** 重试超限 / 不可重试：进死信，原样留现场 */
    public void deadLetter(FollowUpTask task) {
        send(RabbitConfig.FOLLOW_UP_DEAD_QUEUE, task);
    }

    private void send(String routingKey, Object task) {
        try {
            String payload = mapper.writeValueAsString(task);
            rabbit.convertAndSend(routingKey, payload, message -> {
                // persistent：broker 重启后消息不丢。
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            log.info("已投递追问任务 queue={} payload={}", routingKey, payload);
        } catch (JsonProcessingException e) {
            // 载荷是 record + 基本类型，序列化失败属于程序错误，直接抛
            throw new IllegalStateException("无法序列化追问任务", e);
        }
    }
}
