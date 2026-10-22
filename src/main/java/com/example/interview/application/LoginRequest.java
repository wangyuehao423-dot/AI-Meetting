package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

// 登录接口接收的请求对象；登录步骤会按 username 查询用户。
public record LoginRequest(
        // 登录时使用的用户名。
        @NotBlank String username,
        // 用于和数据库密码哈希比较的原密码。
        @NotBlank String password
) {
}