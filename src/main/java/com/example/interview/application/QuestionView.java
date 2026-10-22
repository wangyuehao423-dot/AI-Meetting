package com.example.interview.application;

import java.util.UUID;

public record QuestionView(
        // 题目 ID，提交答案时用于定位题目。
        // 前端在提交答案时需要将此 ID 回传，后端据此查找对应的题目记录。
        UUID id,
        // AI 生成并校验后的题目正文。
        // 前端展示给候选人的面试题目文本。
        String content,
        // 题目考察分类。
        // 如 Java、数据库、系统设计等，用于分类展示和能力分析。
        String category,
        // 当前会话中的题目顺序。
        // 从 1 开始递增，决定题目的展示顺序。
        Integer sequenceNo,
        // 题目状态，前端只展示 ACTIVE 题目。
        // 状态为 ACTIVE 表示题目可用，其他状态（如 DISABLED）表示已废弃。
        String status
) {
}
