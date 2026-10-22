package com.example.interview.ai;

// AI 出题方法的返回值；只描述"生成的题长什么样"。
public record GeneratedQuestion(
        String content,
        String category
) {
}