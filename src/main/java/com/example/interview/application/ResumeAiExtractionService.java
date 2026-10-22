package com.example.interview.application;

import com.example.interview.infrastructure.singleflight.FailureClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeAiExtractionService {

    // 系统角色限定为资深技术招聘专家，让 AI 按招聘场景识别技术技能。
    private static final String SYSTEM_PROMPT =
            "你是一位资深技术招聘专家。";

    // **简历技能提取与其他 AI 能力统一通过 ChatClient 调用。**
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    // 失败分类器：识别 429 限流/网络超时等瞬时故障，供重试循环使用。
    private final FailureClassifier failureClassifier;

    public ResumeAiExtractionService(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            FailureClassifier failureClassifier
    ) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.failureClassifier = failureClassifier;
    }

    public String extractSkills(String parsedResumeText) {
        if (parsedResumeText == null || parsedResumeText.isBlank()) {
            throw new IllegalArgumentException(
                    "parsed resume text is empty"
            );
        }

        String raw = callWithRetry(parsedResumeText);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "AI returned empty resume skills"
            );
        }

        // 入库前校验 JSON，防止 AI 返回解释文字或 Markdown。
        return normalizeSkills(
                raw
        );
    }

    // 与门面的 AI 调用一致：只对"瞬时故障"（限流 429 / 网络超时 / 5xx）重试 3 次，
    // 退避递增 600ms / 1200ms。否则上游限流会把整条简历上传链路打成 500。
    private String callWithRetry(String parsedResumeText) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt += 1) {
            try {
                String content = chatClient.prompt()
                        .system(SYSTEM_PROMPT
                                + "\n只提取编程语言、后端框架、数据库、中间件和云原生工具。"
                                + "\n只返回 JSON 字符串数组，不要返回解释。")
                        .user("""
                                请从以下候选人的简历中提取技术技能。
                                简历文本：
                                %s
                                """.formatted(parsedResumeText))
                        .call()
                        .content();
                if (content != null && !content.isBlank()) {
                    return content;
                }
                lastFailure = new IllegalStateException(
                        "AI returned empty resume skills"
                );
            } catch (RuntimeException retryableEx) {
                // 只有被分类为可重试的瞬时故障才重试；其它异常原样向上抛。
                if (failureClassifier.classify(retryableEx)
                        != FailureClassifier.FailureType.RETRYABLE) {
                    throw retryableEx;
                }
                lastFailure = retryableEx;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("AI call failed unexpectedly")
                : lastFailure;
    }

    private String normalizeSkills(String content) {
        try {
            // 只接受 JSON 数组，避免把解释文字或 Markdown 保存到技能字段。
            JsonNode root = objectMapper.readTree(content.trim());
            if (!root.isArray()) {
                throw new IllegalArgumentException("AI resume skills must be a JSON array");
            }

            List<String> skills = new ArrayList<>();
            for (JsonNode skill : root) {
                if (!skill.isTextual() || skill.asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "AI resume skills must contain only non-empty strings"
                    );
                }
                String value = skill.asText().trim();
                // 去重，避免同一个技能重复影响后续匹配结果。
                if (!skills.contains(value)) {
                    skills.add(value);
                }
            }
            return objectMapper.writeValueAsString(skills);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "AI resume skills must be a valid JSON array",
                    e
            );
        }
    }
}