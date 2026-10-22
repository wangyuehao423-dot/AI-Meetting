package com.example.interview.skill;

import org.springframework.core.io.Resource;      // Spring 资源抽象，可表示 classpath、文件系统、URL 等来源
import org.springframework.core.io.ResourceLoader; // Spring 资源加载器，支持 "classpath:" 等前缀
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// ============================================================
// Skill 加载器 —— 读取 markdown 并解析出结构化字段
// ============================================================
// 每个 skill 文件头部有一段 front matter（用 --- 包裹的 key: value 行）：
//   ---
//   purpose: 一句话用途
//   entry-points: A, B
//   inputs: p1, p2
//   output: 输出契约
//   ---
//   正文...
// load() 把元数据剥离出来，剩余正文作为 SkillDefinition.content。
// 没有 front matter 的旧文件也能加载（各元数据字段退化为空），保证向后兼容。
// ============================================================
@Component
public class SkillLoader {
    private final ResourceLoader resources;

    public SkillLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    public SkillDefinition load(String name, String version) {
        Resource resource = resources.getResource("classpath:skills/" + name + ".md");
        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            Meta meta = parse(raw);
            return new SkillDefinition(
                    name,
                    version,
                    meta.purpose(),
                    meta.entryPoints(),
                    meta.inputs(),
                    meta.output(),
                    // 正文去掉首尾空白，避免注入提示词时带上多余换行。
                    meta.body().trim()
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot load skill:" + name,
                    e
            );
        }
    }

    // ============================================================
    // 私有：解析 front matter，返回元数据 + 正文
    // ============================================================
    private Meta parse(String raw) {
        List<String> lines = List.of(raw.split("\r?\n"));
        // 第一行不是 "---"：没有 front matter，整篇都当正文。
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return new Meta("", List.of(), List.of(), "", raw);
        }
        String purpose = "";
        String output = "";
        List<String> entryPoints = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        int index = 1;
        // 逐行读到第二个 "---"，其间的行按 key: value 切分。
        for (; index < lines.size(); index += 1) {
            String line = lines.get(index);
            if (line.trim().equals("---")) {
                index += 1;   // 再跳一行，index 指向正文起点
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "purpose" -> purpose = value;
                case "output" -> output = value;
                case "entry-points" -> entryPoints = splitComma(value);
                case "inputs" -> inputs = splitComma(value);
                default -> { /* 未知 key 忽略，不阻塞加载 */ }
            }
        }
        // 正文 = front matter 之后的所有行。
        String body = String.join("\n", lines.subList(index, lines.size()));
        return new Meta(purpose, entryPoints, inputs, output, body);
    }

    // 把 "a, b, c" 拆成去空白、滤空串的字符串列表（元数据里 entry-points/inputs 都是逗号分隔）。
    private List<String> splitComma(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ============================================================
    // 内部记录：front matter 解析结果
    // ============================================================
    private record Meta(
            String purpose,
            List<String> entryPoints,
            List<String> inputs,
            String output,
            String body
    ) {
    }
}