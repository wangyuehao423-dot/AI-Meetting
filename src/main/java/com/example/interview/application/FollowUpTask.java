package com.example.interview.application;

import java.util.UUID;

/**
 * 追问任务的不可变数据模型，作为 Redis 队列中传递的载荷。
 *
 * @param sessionId  面试会话 ID，用于关联会话数据
 * @param questionId 题目 ID，用于关联当前题目
 * @param score      当前题的评分（0-100），用于决定追问深度
 * @param retryCount 当前重试次数，从 0 开始，超过上限进入死信队列
 */
public record FollowUpTask(
        UUID sessionId,
        UUID questionId,
        double score,
        int retryCount
) {
}
