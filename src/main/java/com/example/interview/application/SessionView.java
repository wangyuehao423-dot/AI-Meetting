package com.example.interview.application;

import com.example.interview.domain.InterviewState;

import java.util.UUID;

// 面试会话接口的响应对象，只表达当前会话进度。
public record SessionView(
        // 面试会话 ID，后续答题和查询都使用它。
        UUID sessionId,
        // 当前状态机状态，前端据此决定显示哪个页面。
        InterviewState state,
        // 快照版本号，用于观察状态是否已经推进。
        long version
) {
}