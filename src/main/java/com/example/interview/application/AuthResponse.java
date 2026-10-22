package com.example.interview.application;

// 注册和登录共用的响应对象，避免前端处理两种返回格式。
public record AuthResponse(
        // 服务端签发的 JWT，前端后续请求会放入 Authorization。
        String token,
        // 当前用户 ID，用于关联后续业务数据。
        String userId,
        // 当前用户登录名。
        String username,
        // 当前用户展示名称。
        String displayName
) {
}