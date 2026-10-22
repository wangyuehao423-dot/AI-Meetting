package com.example.interview.application;

import java.util.UUID;

// 从这里开始是新增的：追问视图对象，回答"候选人答完原题之后，这道题有没有追问要答"。
// 为什么需要它：追问内容存在 interview_follow_up 表，不作为新题插入 interview_question，
// 所以前端在提交完原题答案、拿到下一题之前，必须先问一次"刚才这道题有没有追问"。
// 没有这个视图，前端就无从知道该不该用 followUp=true 再提交一次，is_follow_up 链路
// 在后端虽然通了，界面上却永远走不到。
// 只暴露前端渲染需要的三个字段，不把整行实体丢出去（level 用来区分展示样式）。
public record FollowUpView(
        // 追问挂在哪一道原题上。前端拿它当 questionId 提交追问答案，与原题保持一致。
        UUID questionId,
        // 追问等级：基础追问 / 常规追问 / 深度追问，前端据此调整标题文案。
        String level,
        // AI 生成的追问正文（Markdown），前端直接渲染成题目。
        String content
) {
}
