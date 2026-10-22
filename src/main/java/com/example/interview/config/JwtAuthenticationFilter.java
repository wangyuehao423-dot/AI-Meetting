package com.example.interview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// OncePerRequestFilter 保证一次 HTTP 请求只执行一次过滤逻辑。
public class JwtAuthenticationFilter
        extends OncePerRequestFilter {

    // JWT 服务依赖，负责签发 Token 和解析 Token 身份。
    private final JwtTokenService tokens;

    // 过滤器只依赖 Token 解析服务，不直接访问数据库。
    public JwtAuthenticationFilter(
            JwtTokenService tokens
    ) {
        this.tokens = tokens;
    }

    @Override
    // 在 Controller 前检查 Authorization 头；Token 有效则放行，无效则返回 401。
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        // 每个请求先从 Authorization 头读取 Bearer Token。
        var header =
                request.getHeader("Authorization");

        if (header != null
                && header.startsWith("Bearer ")) {
            try {
                // 去掉 "Bearer " 前缀后才是 JWT 本体。
                var username = tokens.username(
                        header.substring(7)
                );
                // 把身份放入当前请求，后续 Controller 可以读取。
                request.setAttribute(
                        "userName",
                        username
                );
                request.setAttribute(
                        "userId",
                        tokens.userId(
                                header.substring(7)
                        )
                );
            } catch (Exception ignored) {
                // Token 无法验证时不能继续访问受保护接口。
                response.setStatus(401);
                return;
            }
        }

        // 校验完成后交给下一个过滤器或 Controller。
        chain.doFilter(request, response);
    }
}