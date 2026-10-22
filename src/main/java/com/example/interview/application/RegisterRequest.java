package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

// 注册接口接收的请求对象；@NotBlank 会在方法执行前拦截空值。
public record RegisterRequest(
        // 用户登录名，后面会用它检查数据库中的唯一性。
        @NotBlank String username,
        // 原始密码只交给 PasswordEncoder，不直接入库。
        @NotBlank String password,
        // 页面展示用昵称，不参与密码认证。
        @NotBlank String displayName
) {
}
