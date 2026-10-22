package com.example.interview.application;

import java.util.UUID;

/**
 * 评分任务在 Redis 队列里的载荷对象。
 * <p>
 * 为什么用 record：
 * 1. 任务是只读的，投递进队列之后不会再被修改；
 * 2. 只装"重新执行一次评分"所必需的最小上下文，消费者与补偿线程拿到后据此重建评分调用；
 * 3. 字段都是基本类型 + String + UUID，Jackson 序列化 / 反序列化不会出意外。
 * </p>
 * <p>
 * 注意：这个类只描述"队列里传什么"。AI 返回后解析出来的结果对象是
 * {@link JudgeResult}（score / feedback / fencingToken / stale），两者字段不同，
 * 不要混用。本文件之前被误写成了 JudgeResult 的字段，导致
 * {@code JudgeTaskPublisher}、{@code JudgeTaskCompensationService} 全部编译不过，
 * 现已改回队列载荷的形状。
 * </p>
 *
 * @param sessionId  面试会话 ID，消费者靠它取会话快照、推进状态机
 * @param questionId 题目 ID
 * @param answer     候选人答案原文，评分时直接送给 AI
 * @param followUp   是否追问作答：false=初次作答、true=追问作答。
 *                   消费者据此决定评分写回哪一行（is_follow_up），以及是否推进主线状态机；
 *                   重试 / 补偿重投时必须原样保留，否则追问任务会被当成初次作答错误处理。
 * @param retryCount 已重试次数：投递时为 0，重试与补偿各 +1，超过上限转死信队列
 */
public record JudgeTask(
    UUID sessionId,
    UUID questionId,
    String answer,
    // 从这里开始是新增的：追问标记插在 retryCount 之前，retryCount 仍保持末位，
    // 这样"重试次数永远是最后一个分量"的约定不变，阅读和调用时更不容易错位。
    boolean followUp,
    // 新增结束
    int retryCount
) {
}