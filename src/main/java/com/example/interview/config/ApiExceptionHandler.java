// 从这里开始是新增的：以下整个文件都是新增的（磁盘上原是一个只有类头的空类，
// 报告接口抛出的 AccessDeniedException 没人接，会一路冒到 Spring MVC 默认处理变成 500）。
package com.example.interview.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
            IllegalArgumentException error
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", error.getMessage()));
    }

    // 从这里开始是新增的：会话状态非法操作（如状态机非法转移 invalid transition、
    // 快照版本冲突）一律映射为 409 Conflict，而不是 500。
    // 为什么必须加：这些错误本质是"会话当前状态不允许该操作"——是业务冲突不是服务故障，
    // 前端看到 409 才知道是状态时序问题（稍后重试/刷新即可），500 只会被当成后端崩了。
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(
            IllegalStateException error
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", error.getMessage()));
    }
    // 新增结束

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(
            AccessDeniedException error
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", error.getMessage()));
    }
}
// 新增结束
