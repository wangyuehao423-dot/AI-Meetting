package com.example.interview.application;

// 一次已评分答案的查询结果，用于把评分数据从仓库层返回给上层逻辑。
// 记录 AI 给出的分数、反馈以及用于防止旧结果覆盖新结果的围栏令牌。
public class StoredJudge {
    // AI 评分结果，可能为 null 表示尚未评分。
    // 与 AnswerEntity 的 score 列（Double）保持一致，支持小数评分。
    private final Double score;
    // AI 评分反馈，包含评语和改进建议。
    private final String feedback;
    // 围栏令牌，用于防止旧评分结果覆盖新评分结果。
    private final Long fencingToken;

    public StoredJudge(Double score, String feedback, Long fencingToken) {
        this.score = score;
        this.feedback = feedback;
        this.fencingToken = fencingToken;
    }

    public Double score() {
        return score;
    }

    public String feedback() {
        return feedback;
    }

    public Long fencingToken() {
        return fencingToken;
    }
}