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
 * 报告任务投递器。
 *
 * <h3>为什么报告也要走队列，而不是继续用 @Async</h3>
 * 报告和评分本来就是同一类任务：要异步、要状态、失败要能重投。
 * <ul>
 *   <li><b>@Async 的真实缺口</b>：JVM 崩溃时，正在生成的报告任务直接蒸发，
 *       而 report 行还停在 GENERATING——没人会来救它，前端永远转圈。
 *       走队列之后，消息没 ack 就自动 requeue，这个缺口被补上。</li>
 *   <li><b>统一机制</b>：评分已经用 RabbitMQ 了，报告再单独用线程池，
 *       等于系统里同时维护两种异步模型。统一之后少一套心智负担。</li>
 * </ul>
 *
 * <p>状态仍然落库（GENERATING / COMPLETED / FAILED），队列负责「任务不丢、失败可重投」，
 * 状态字段负责「前端可查」。两者职责不重叠。
 */
@Service
public class ReportTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReportTaskPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public ReportTaskPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    public void publish(UUID sessionId, Long reportId) {
        send(RabbitConfig.REPORT_QUEUE, new ReportTask(sessionId, reportId, 0));
    }

    /** 可重试失败：投延迟队列，TTL 到期后经死信交换机自动回主队列 */
    public void retryLater(ReportTask task) {
        send(RabbitConfig.REPORT_RETRY_QUEUE, task);
    }

    /**
     * 重试超限 / 不可重试：进死信，原样留现场给运维。
     *
     * <p>报告与评分走同一套「主队列 + 延迟重试 + 死信」结构，理由有两条：
     * <ul>
     *   <li><b>失败是有区别的</b>：AI 超时、限流是瞬时抖动，隔 10 秒重跑大概率能成；
     *       而「整场没答几道题、上下文拼不出来」是结构性失败，重跑还是失败。
     *       延迟重试 + 上限 3 次正好把这两类分开——前者在第二、三次自愈，后者落到死信。</li>
     *   <li><b>同一套语义</b>：三类任务共用一种重试机制，运维只盯一个控制台，
     *       不会出现「评分能重试、报告不能」这种要背下来的差异。</li>
     * </ul>
     *
     * <p>重试期间记录保持 GENERATING，不进 FAILED——一旦写成终态，前端会先显示
     * 「生成失败」再跳回「生成中」，比多等十几秒更让人困惑。真正失败由消费者在
     * 重试耗尽时统一置 FAILED，用户仍可点「重新生成」手动再投一次。
     */
    public void deadLetter(ReportTask task) {
        send(RabbitConfig.REPORT_DEAD_QUEUE, task);
    }

    private void send(String routingKey, Object task) {
        try {
            String payload = mapper.writeValueAsString(task);
            rabbit.convertAndSend(routingKey, payload, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            log.info("已投递 queue={} payload={}", routingKey, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化报告任务: " + task, e);
        }
    }
}
