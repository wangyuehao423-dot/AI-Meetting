package com.example.interview.skill;

import java.time.Instant;
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
//
// 改造后新增两个字段：
//   source    —— 这份规则是从哪来的：nacos（配置中心在线托管）/ classpath（jar 内打包兜底）
//   updatedAt —— 本份规则被加载生效的时间
// 为什么要它们：提示词搬到 Nacos 之后，"线上跑的到底是哪一版"变成了一个必须能随时回答的问题。
// 只靠日志回答不够——出问题时第一反应是调接口看当前生效值，接口里必须能直接看到来源和生效时间。
//
// version 的语义也随之改变：以前是写死的 "v1"，现在改成"规则全文的指纹"（MD5 前 8 位）。
// 常量版本号的问题在于它永远不变——改了内容版本还是 v1，等于没有版本。
// 用内容指纹才能回答"昨天好好的今天变差了，中间是不是换过规则"：比一下 version 就知道。
// ============================================================
public record SkillDefinition (
    String name,
    String version,
    String purpose,
    List<String> entryPoints,
    List<String> inputs,
    String outputContract,
    String content,
    String source,
    Instant updatedAt
) {}
