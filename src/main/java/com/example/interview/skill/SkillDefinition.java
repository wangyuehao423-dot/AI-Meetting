package com.example.interview.skill;

import java.util.List;

// ============================================================
// Skill 定义 —— 结构化的业务技能，可被 Code Agent 直接消费
// ============================================================
// 原本只是 (name, version, content) 三字段的裸 Markdown 壳，
// 升级后把"技能元数据 + 规则正文"拆成可编程访问的字段：
//   name/version     —— 标识与版本（Code Agent 用来寻址）
//   purpose          —— 一句话用途（概述）
//   entryPoints      —— 调用入口（自动消费点，标识"哪些链路会用到它"）
//   inputs           —— 输入契约（参数名清单）
//   outputContract   —— 输出契约（返回结构与格式约束）
//   content          —— 规则主体（Markdown，注入提示词的正文）
//
// 为什么这样分层："可被 Code Agent 直接消费"意味着 Skill 要能被程序读懂、
// 而不只是塞给大模型的一段文本。元数据字段让代码能按用途/入口/契约检索和调度，
// content 则是最底层的"规则正文"，由统一注入点（InterviewAiFacade.skillPrompt）
// 自动拼进提示词。整条定义经 SkillController 以 JSON 暴露给外部消费方。
// ============================================================
public record SkillDefinition (
    String name,
    String version,
    String purpose,
    List<String> entryPoints,
    List<String> inputs,
    String outputContract,
    String content
) {}