package com.example.interview.application;

import com.example.interview.config.RabbitConfig;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 报告消费者：取任务 → 生成 → 写回状态 → ack。
 *
 * <h3>失败走重试，重试耗尽才落终态</h3>
 * 与评分、追问同一套结构：可重试失败投延迟重试队列（TTL 10 秒后经 DLX 回主队列），
 * 超过 {@code MAX_RETRY} 次转死信并置 FAILED。
 * <ul>
 *   <li><b>为什么要重试</b>：报告失败里有一大块是 AI 超时、限流这类瞬时抖动，
 *       隔 10 秒重跑一次的成本远低于让用户手动点「重新生成」。</li>
 *   <li><b>为什么只重试 3 次</b>：另一块失败是结构性的（整场没答几道题、上下文拼不出来），
 *       重跑还是失败。上限 3 次把两类分开，既自愈又不把资源放大到无限。</li>
 *   <li><b>为什么重试期间不写 FAILED</b>：一旦落终态，用户会先看到「生成失败」，
 *       几秒后又跳回「生成中」，比多等十几秒更让人以为系统出毛病。</li>
 * </ul>
 *
 * <h3>ack 的位置</h3>
 * 正常路径：正文写回 + 状态置 COMPLETED 之后 ack。
 * 失败路径：投完重试队列 / 死信之后 ack。
 * 两条路都 ack，是因为「已交给下一跳」也是一种已处理状态——不 ack 会让消息在
 * unacked 里挂着，连接断开后被重投，反而变成无限热重试。
 */
@Component
public class ReportTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportTaskConsumer.class);

    /** 与评分任务一致：最多重试 3 次，每次间隔 10 秒（由重试队列 TTL 决定） */
    private static final int MAX_RETRY = 3;

    private final ReportGenerationAsyncService generation;
    private final ReportTaskPublisher publisher;
    private final ObjectMapper mapper;
    // 从这里开始是新增的：报告表 Mapper，用于消费前判重。
    private final ReportMapper reports;
    // 新增结束

    public ReportTaskConsumer(ReportGenerationAsyncService generation,
                              ReportTaskPublisher publisher,
                              ObjectMapper mapper,
                              ReportMapper reports) {
        this.generation = generation;
        this.publisher = publisher;
        this.mapper = mapper;
        this.reports = reports;
    }

    @RabbitListener(queues = RabbitConfig.REPORT_QUEUE)
    public void onReport(String payload, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        ReportTask task = null;
        try {
            task = mapper.readValue(payload, ReportTask.class);
            // 从这里开始是新增的：消费端幂等。
            // 为什么生产端已经拦过还要再拦一道：生产端的判断基于「10 分钟内算正在跑」，
            // 挡不住两种漏网——① 两条消息在毫秒级同时入队（都还没写状态）；
            // ② 之前重复投递遗留在队列里的旧消息。这两条都进来后，
            // 第一份生成完会把状态写成 COMPLETED，第二条如果不判重就会把
            // 一份已经好的报告清空重做，用户看到已完成的报告又变回「生成中」。
            ReportEntity current = reports.selectById(task.reportId());
            if (current != null
                    && "COMPLETED".equals(current.getStatus())
                    && current.getContent() != null
                    && !current.getContent().isBlank()) {
                log.info("报告已生成，跳过重复任务 reportId={} sessionId={}",
                        task.reportId(), task.sessionId());
                channel.basicAck(tag, false);
                return;
            }
            // 新增结束
            if (!generation.generate(task.sessionId(), task.reportId())) {
                // 可重试失败：不在 generate 里落 FAILED，交给这里决定下一跳
                retryOrDead(task, channel, tag);
                return;
            }
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // 走到这里的多半是「记录被删」「Mapper 异常」这类绕过 generate 内部 catch 的问题，
            // 以及消息体本身反序列化失败（此时 task 为 null）。
            log.error("报告任务处理失败，按可重试失败处理: {}", payload, e);
            retryOrDead(task, channel, tag);
        }
    }

    /**
     * 重试或进死信。
     *
     * <p>重试投延迟队列而不是 {@code basicNack(requeue=true)}：requeue 会把消息立刻放回队首，
     * 一个正在限流的模型接口会被连续打三次、三次全败直接进死信，重试等于没重试。
     * 隔 10 秒再回来，才给下游留出恢复窗口。
     *
     * <p>重试耗尽时先 {@code markFailed} 再进死信：死信是给运维看的现场，
     * FAILED 是给前端看的终态，两者缺一个都会让人以为报告还在生成。
     */
    private void retryOrDead(ReportTask task, Channel channel, long tag) throws IOException {
        if (task == null) {
            // 消息体本身解析不了：重投也还是解析不了，直接 ack 掉并留日志
            channel.basicAck(tag, false);
            return;
        }
        if (task.retryCount() >= MAX_RETRY) {
            log.error("报告重试超限，置 FAILED 并转死信 reportId={} retryCount={}",
                    task.reportId(), task.retryCount());
            generation.markFailed(task.reportId());
            publisher.deadLetter(task);
        } else {
            log.warn("报告生成失败，10 秒后重试 reportId={} 第 {}/{} 次",
                    task.reportId(), task.retryCount() + 1, MAX_RETRY);
            publisher.retryLater(new ReportTask(
                    task.sessionId(), task.reportId(), task.retryCount() + 1));
        }
        channel.basicAck(tag, false);
    }
}
