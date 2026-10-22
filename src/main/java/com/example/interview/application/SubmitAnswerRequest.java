package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

// HTTP 层接收的答案提交参数，转换后才交给 AnswerCommand。
public record SubmitAnswerRequest(
        // 当前题目的 ID，后端据此查找对应的题目记录。
        UUID questionId,
        // 客户端生成的请求 ID，用于幂等去重，不能为空。
        @NotBlank String requestId,
        // 候选人提交的答案正文，不能为空。
        @NotBlank String answer,
        // 从这里开始是新增的：追问作答标记，由前端提交。
        // 用包装类型 Boolean 而不是基本类型 boolean，是为了允许前端老版本不传该字段——
        // 缺省时 Jackson 给 null，控制器里用 Boolean.TRUE.equals(...) 兜底成 false（初次作答），
        // 这样新老前端都能正常提交，不会因为缺字段直接 400。
        Boolean followUp
        // 新增结束
) {
}