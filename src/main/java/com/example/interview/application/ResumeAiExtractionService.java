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

    // 格式坏掉时最多再问一次模型。模型偶发把数组包进 ```json 围栏或加一句解释，
    // 这种抖动重试一次基本就恢复，没必要把整条上传链路判死。
    private static final int FORMAT_ATTEMPTS = 2;

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
        // 简历正文为空是上传侧的问题（空文件/无可抽取文本），仍按 400 处理。
        if (parsedResumeText == null || parsedResumeText.isBlank()) {
            throw new IllegalArgumentException(
                    "parsed resume text is empty"
            );
        }

        RuntimeException lastFailure = null;
        for (int round = 0; round < FORMAT_ATTEMPTS; round += 1) {
            String raw = callWithRetry(parsedResumeText);
            if (raw == null || raw.isBlank()) {
                // 模型回了空串：这是上游故障，不是客户端参数错。
                lastFailure = new AiUpstreamException(
                        "AI 未返回简历技能，请稍后重试"
                );
                continue;
            }
            try {
                // 入库前校验并归一化 JSON，防止把解释文字或 Markdown 存进技能字段。
                return normalizeSkills(raw);
            } catch (MalformedSkillsException malformed) {
                // 格式不对：记下来，再问一次模型。
                lastFailure = malformed;
            }
        }

        throw new AiUpstreamException(
                "AI 技能抽取返回格式异常，请稍后重试",
                lastFailure
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
                                + "\n只返回 JSON 字符串数组，不要返回解释，不要使用 Markdown 代码围栏。")
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
                lastFailure = new AiUpstreamException(
                        "AI 未返回简历技能，请稍后重试"
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

    /**
     * 把模型输出归一化成 JSON 字符串数组。
     *
     * <p>模型即使被要求"只返回数组"，仍常见两种走偏：外面裹一层 ```json 围栏、
     * 或在数组前后加一句"以下是提取结果"。两种都当成格式问题重试或截取，
     * 而不是直接判失败。</p>
     */
    private String normalizeSkills(String content) {
        String cleaned = stripCodeFence(content.trim());
        JsonNode root = tryParse(cleaned);

        // 兜底：从第一个 '[' 到最后一个 ']' 截取一段再试一次。
        if (root == null || !root.isArray()) {
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                JsonNode sliced = tryParse(cleaned.substring(start, end + 1));
                if (sliced != null && sliced.isArray()) {
                    root = sliced;
                }
            }
        }

        if (root == null || !root.isArray()) {
            throw new MalformedSkillsException(
                    "AI resume skills must be a JSON array"
            );
        }

        List<String> skills = new ArrayList<>();
        for (JsonNode skill : root) {
            if (!skill.isTextual() || skill.asText().isBlank()) {
                throw new MalformedSkillsException(
                        "AI resume skills must contain only non-empty strings"
                );
            }
            String value = skill.asText().trim();
            // 去重，避免同一个技能重复影响后续匹配结果。
            if (!skills.contains(value)) {
                skills.add(value);
            }
        }
        try {
            return objectMapper.writeValueAsString(skills);
        } catch (Exception serialization) {
            throw new MalformedSkillsException(
                    "AI resume skills cannot be serialized"
            );
        }
    }

    // 去掉 ```json ... ``` / ``` ... ``` 围栏，只留中间正文。
    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    private JsonNode tryParse(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception unparsable) {
            return null;
        }
    }

    // 模型返回的格式不对（不是数组 / 元素不是非空字符串）。
    // 内部专用，最终统一包成 AiUpstreamException 出去。
    private static class MalformedSkillsException extends RuntimeException {
        MalformedSkillsException(String message) {
            super(message);
        }
    }
}
