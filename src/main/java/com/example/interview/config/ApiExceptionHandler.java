// 从这里开始是新增的：以下整个文件都是新增的（磁盘上原是一个只有类头的空类，
// 报告接口抛出的 AccessDeniedException 没人接，会一路冒到 Spring MVC 默认处理变成 500）。
package com.example.interview.config;

import com.example.interview.application.AiUpstreamException;
import com.example.interview.application.AuthException;
import com.example.interview.application.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    // 从这里开始是新增的：两个 HTTP 语义修正。
    // 401：认证失败（密码错、没带 token、token 无有效身份）。
    // 之前这些抛的是 IllegalArgumentException / IllegalStateException，
    // 分别被映射成 400 / 409——前端只会当成"参数错/状态冲突"，不会跳登录页。
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(
            AuthException error
    ) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", error.getMessage()));
    }

    // 404：目标资源不存在（岗位/简历/报告）。之前是 400，语义同样偏了。
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(
            NotFoundException error
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", error.getMessage()));
    }
    // 新增结束

    // 从这里开始是新增的：502 上游模型故障。
    // 简历技能抽取失败（超时 / 限流重试耗尽 / 模型返回的不是 JSON 数组）属于
    // "服务端依赖出错"，不是客户端参数错。早先抛 IllegalArgumentException 会被
    // 映射成 400，前端只会当成自己传错了文件、不会重试。502 才表达"网关后端坏了"。
    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<Map<String, Object>> badGateway(
            AiUpstreamException error
    ) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("message", error.getMessage()));
    }
    // 新增结束

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
