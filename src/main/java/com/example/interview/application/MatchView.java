package com.example.interview.application;

// 岗位匹配接口 /api/positions/{id}/match 返回的响应对象
public record MatchView(
        // 简历 ID
        Long resumeId,
        // 岗位 ID
        Long positionId,
        // 匹配分数（百分比，如 85.5）
        Double matchScore,
        // 匹配详情（例如："匹配技能：Java、Spring、MySQL"）
        String detail
) {
}