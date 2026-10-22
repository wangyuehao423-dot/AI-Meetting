package com.example.interview.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// @Service 让 JWT 服务可以被 Controller 和 Filter 注入。
@Service
public class JwtTokenService {

    private final SecretKey key;

    public JwtTokenService(
            @Value("${interview.jwt-secret:}") String secret
    ) {
        // 密钥太短会降低 HMAC 签名的安全性，配置错误时直接阻止服务启动。
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "INTERVIEW_JWT_SECRET must be at least 32 characters"
            );
        }

        // JWT 的签发和解析必须使用同一把密钥。
        key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String issue(Long userId, String username) {
        return Jwts.builder()
                // subject 保存主要身份；业务字段用 claim 保存。
                .subject(username)
                .claim("userId", String.valueOf(userId))
                .issuedAt(new Date())
                // 当前示例把 Token 有效期设置为 24 小时。
                .expiration(new Date(
                        System.currentTimeMillis()
                                + 86400000
                ))
                // 签名让服务端能够识别 Token 是否被篡改。
                .signWith(key)
                .compact();
    }

    public String username(String token) {
        return Jwts.parser()
                // verifyWith 会校验签名，过期或篡改都会抛异常。
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Long userId(String token) {
        // 解析 JWT 的 userId 声明，与 issue() 中 .claim("userId", ...) 对应。
        String raw = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("userId", String.class);
        return raw != null ? Long.parseLong(raw) : null;
    }
}