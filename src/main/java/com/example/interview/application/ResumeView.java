package com.example.interview.application;

// 简历接口返回的只读对象，避免把 ResumeEntity 直接暴露给前端。
public record ResumeView(
        // 简历记录 ID。
        String id,
        // 简历所属用户 ID。
        String userId,
        // 用户上传时的文件名。
        String fileName,
        // 当前解析状态，例如 PARSING 或 PARSED。
        String status,
        // 解析出的正文，供准备页面和匹配模块使用。
        String parsedContent,
        // **AI 提取出的技术技能 JSON，供岗位匹配和前端展示使用。**
        String extractedSkills
) {
}