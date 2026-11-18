package com.example.interview.application;

import java.util.UUID;

/**
 * 报告生成任务的队列载荷。
 *
 * <p>只带三个字段：sessionId 用来查数据，reportId 用来定位要更新的那一行，
 * retryCount 用来数重试次数。任务体不塞上下文——上下文是查库拼出来的，
 * 塞进消息体既会让消息膨胀，也会出现「消息里的上下文和库里不一致」的幽灵数据。
 *
 * <p>retryCount 与评分任务同义：投递时为 0，每次转延迟重试队列 +1，
 * 超过上限转死信。它必须跟着消息走，不能只在内存里数——消费者是多实例的，
 * 同一条消息可能被不同节点接到。
 *
 * @param sessionId  面试会话 ID
 * @param reportId   报告记录主键
 * @param retryCount 已重试次数，投递时为 0
 */
public record ReportTask(UUID sessionId, Long reportId, int retryCount) {
}
