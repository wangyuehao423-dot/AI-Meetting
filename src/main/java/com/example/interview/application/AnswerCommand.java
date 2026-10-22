package com.example.interview.application;

import java.util.UUID;

public record AnswerCommand(
        // 面试会话 ID，用来贯穿题目、答案、评分和报告。
        // 标识答案属于哪一场面试。
        UUID sessionId,
        // 当前题目 ID，提交答案和评分时用它定位题目。
        // 标识答案对应哪一道面试题。
        UUID questionId,
        // 客户端生成的请求 ID，用来识别网络重试。
        // 每次提交使用唯一 ID，相同 requestId 的重复提交会被幂等处理。
        String requestId,
        // 候选人本次提交的答案正文。
        // 用户输入或语音转写的文本内容。
        String answer,
        // 从这里开始是新增的：追问作答标记。
        // false=初次作答（候选人第一次回答原题），true=追问作答（候选人回答的是原题派生出的追问）。
        // 为什么放在命令对象里：追问不作为新题插入 interview_question，前端提交的 questionId
        // 仍是原题 ID，所以只能靠这个标记在落库、评分、状态机各环节区分两种作答。
        boolean followUp
        // 新增结束
) {
}