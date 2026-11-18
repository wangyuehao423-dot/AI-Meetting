package com.example.interview.skill;

import java.util.List;
import java.util.Set;

// ============================================================
// Skill 目录 —— "系统里有哪些 Skill" 的唯一清单
// ============================================================
// 为什么要单独抽一个常量类：
//   改造前这份清单硬编码在 SkillRegistry.registerDefaults() 里，而 dataId 命名规则又在
//   发布脚本里各写一份，两边一旦不一致，就会出现"脚本推到 A、程序去 B 找"的静默故障
//   （表现是：明明发布了配置，程序却一直用 classpath 版本，日志里看不出任何异常）。
//   集中到这里之后，注册、兜底、订阅、发布脚本核对都引用同一份来源。
// ============================================================
public final class SkillCatalog {

    // 顺序即"业务上五条 AI 链路的顺序"，不承载任何优先级语义。
    public static final List<String> NAMES = List.of(
            "question-generation",   // 出题
            "interview-scoring",     // 评分
            "follow-up-decision",    // 追问决策
            "report-review",         // 报告生成
            "resume-matching"        // 简历与岗位匹配
    );

    public static final Set<String> NAME_SET = Set.copyOf(NAMES);

    private SkillCatalog() {
    }
}
