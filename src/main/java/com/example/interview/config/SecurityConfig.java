package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<
            JwtAuthenticationFilter
            > jwtFilter(
            JwtTokenService tokens
    ) {
        var bean =
                // FilterRegistrationBean 把自定义 Filter 注册到 Servlet 过滤器链。
                new FilterRegistrationBean<>(
                        new JwtAuthenticationFilter(tokens)
                );
        bean.addUrlPatterns("/api/*");
        bean.setOrder(1);
        return bean;
    }
}