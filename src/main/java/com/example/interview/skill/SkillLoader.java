package com.example.interview.skill;

import org.springframework.core.io.Resource;      // Spring 资源抽象，可表示 classpath、文件系统、URL 等来源
import org.springframework.core.io.ResourceLoader; // Spring 资源加载器，支持 "classpath:" 等前缀
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// ============================================================
// Skill 加载器 —— 把一段 Markdown 原文解析成结构化的 SkillDefinition
// ============================================================
// 每个 skill 文件头部有一段 front matter（用 --- 包裹的 key: value 行）：
//   ---
//   purpose: 一句话用途
//   entry-points: A, B
//   inputs: p1, p2
//   output: 输出契约
//   ---
//   正文...
// parse() 把元数据剥离出来，剩余正文作为 SkillDefinition.content。
// 没有 front matter 的旧文件也能加载（各元数据字段退化为空），保证向后兼容。
//
// 改造后有两个入口，共用同一套解析逻辑：
//   loadFromClasspath(name)  —— 读 jar 内 resources/skills/xxx.md，作为兜底
//   parse(name, raw, source) —— 解析"从外面拿到的字符串"，Nacos 拿来的走这条
//
// 为什么兜底入口必须留着，而不是"上了 Nacos 就把本地文件删掉"：
//   本地文件是这份规则的"出厂版本"。Nacos 挂了、配置被人误删、新环境还没推配置，
//   这三种情况都必须能起来并跑出一个可用的提示词。本地文件在，最差也只是回到改造前的状态；
//   本地文件不在，就只能抛异常或者拼一段空规则——后者更糟，等于静默降级。
//
// 版本号：取规则全文的 MD5 前 8 位，而不是写死 "v1"。
//   写死的版本号永远不会变，改了内容还是同一个版本，等于没有版本；
//   内容指纹能直接回答"这版和昨天那版是不是同一份规则"。
//   只取前 8 位：够用（碰撞概率可忽略），也够短（日志里一行能塞下）。
// ============================================================
@Component
public class SkillLoader {

    // 来源标记，会随 SkillDefinition 一起暴露到 /api/skills，用于确认线上生效的是哪一份。
    public static final String SOURCE_CLASSPATH = "classpath";
    public static final String SOURCE_NACOS = "nacos";

    private final ResourceLoader resources;

    public SkillLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    /**
     * 读取 jar 内打包的规则文件（兜底来源）。
     *
     * <p>读不到直接抛异常。这一点没得商量：如果是启动阶段兜底失败，
     * 说明这个包本身就是坏的（缺 resources），早点启动失败比带着缺失的规则对外服务安全。
     */
    public SkillDefinition loadFromClasspath(String name) {
        Resource resource = resources.getResource("classpath:skills/" + name + ".md");
        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            return parse(name, raw, SOURCE_CLASSPATH);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load skill:" + name, e);
        }
    }

    /**
     * 解析一段规则原文。
     *
     * <p>同一个方法既服务 Nacos 拉下来的内容，也服务 classpath 文件内容——
     * 两条来源走同一套解析逻辑，才能保证"本地怎么写的、Nacos 上就怎么写"，
     * 不会出现两份内容格式要求不一样的情况。
     *
     * @param name   技能名
     * @param raw    Markdown 全文
     * @param source 来源标记（nacos / classpath）
     */
    public SkillDefinition parse(String name, String raw, String source) {
        if (raw == null || raw.isBlank()) {
            // 空内容绝不能解析成"没有规则的 Skill"：那会让提示词少一整段约束，
            // 模型仍然会返回一个看起来正常的答案，错误被静默吞掉。
            throw new IllegalStateException("skill content is blank: " + name + ", source=" + source);
        }
        Meta meta = splitFrontMatter(raw);
        String body = meta.body().trim();
        if (body.isEmpty()) {
            // 只剩 front matter、正文被删空：这类内容能通过 Nacos 发布（内容非空），
            // 但解析后正文是空的，等于一条没有规则的 Skill。
            // 必须在这里拦下——否则提示词里只剩"用途 + 输出契约"，模型照样能答，
            // 只是行为悄悄退化，没有任何报错，等效果变差时已经找不到原因。
            throw new IllegalStateException(
                    "skill body is empty: " + name + ", source=" + source + "（只剩 front matter，正文被删空）");
        }
        return new SkillDefinition(
                name,
                fingerprint(raw),
                meta.purpose(),
                meta.entryPoints(),
                meta.inputs(),
                meta.output(),
                // 正文去掉首尾空白，避免注入提示词时带上多余换行。
                body,
                source,
                Instant.now()
        );
    }

    // 内容指纹：整篇原文（含 front matter）的 MD5 前 8 位。
    // 含 front matter 是有意的——只改 purpose 这类元数据也应该算一次版本变更。
    private String fingerprint(String raw) {
        String md5 = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        return md5.substring(0, 8);
    }

    // ============================================================
    // 私有：解析 front matter，返回元数据 + 正文
    // ============================================================
    private Meta splitFrontMatter(String raw) {
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
