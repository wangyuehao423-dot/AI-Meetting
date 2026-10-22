package com.example.interview.application;

/**
 * AI 评分经过解析和业务校验后的结果对象。
 * <p>
 * 为什么用 record：
 * 1. 评分结果是不可变的，创建后不会修改；
 * 2. record 自动实现了 equals/hashCode，可以安全地作为 Map 的 Key；
 * 3. 在多线程环境下不需要额外的同步措施。
 * </p>
 */
public record JudgeResult(
        double score,
        String feedback,
        long fencingToken,
        boolean stale
) {
}