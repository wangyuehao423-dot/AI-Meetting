package com.example.interview.ai;

import org.springframework.stereotype.Service;

/**
 * 追问决策服务：根据面试评分选择追问策略。
 *
 * <p>职责单一：只做"分数→策略"的映射，不负责执行 LiteFlow 节点。
 * 上游调用方拿到 FollowUpPlan 后，再驱动规则链执行具体的追问流程。</p>
 */
@Service
public class FollowUpDecisionService {

    /**
     * 根据分数选择 LiteFlow 规则链的名称。
     *
     * <p>为什么需要这个方法：上游可能只需要链名（例如直接传给 LiteFlow 的
     * FlowExecutor），而不需要完整的 FollowUpPlan 对象。这个方法提供轻量选择。</p>
     *
     * @param score 本题的评分（0-100），来自评分模块的输出
     * @return LiteFlow 规则链名称，对应 interview.xml 中定义的 chain name
     */
    public String chooseChain(int score) {
        // 分数低于 60：基础薄弱，需要先补概念和核心原理
        if (score < 60) {
            return "followUpBasicChain";
        }
        // 分数 >= 80：表现优秀，可以深入考察边界条件和架构决策
        if (score >= 80) {
            return "followUpDeepChain";
        }
        // 60 <= score < 80：中等水平，补充实现细节和排障过程
        return "followUpNormalChain";
    }

    /**
     * 根据评分生成完整的追问计划，供后续规则链执行。
     *
     * <p>为什么比 chooseChain 更推荐：因为它同时返回了链名、等级和追问目标，
     * 调用方可以据此记录到数据库或传给前端。</p>
     *
     * @param score 本题的评分（0-100）
     * @return 包含链名、等级、追问目标的完整计划
     */
    public FollowUpPlan decide(int score) {
        // 分数低于 60：候选人概念不清或回答严重偏离，需要从基础开始追问
        if (score < 60) {
            return new FollowUpPlan(
                    "followUpBasicChain",   // 对应 LiteFlow 的基础追问链
                    "基础追问",              // 追问等级标签，用于前端展示或数据库记录
                    "补齐概念定义、核心原理和最小可运行例子"  // 追问的具体目标
            );
        }
        // 分数 >= 80：候选人回答很好，可以深入挖掘其知识深度的边界
        if (score >= 80) {
            return new FollowUpPlan(
                    "followUpDeepChain",    // 对应 LiteFlow 的深度追问链
                    "深度追问",
                    "验证边界条件、性能取舍、故障恢复与架构决策"
            );
        }
        // 60 <= score < 80：回答基本正确但不够深入，需要补充细节
        return new FollowUpPlan(
                "followUpNormalChain",      // 对应 LiteFlow 的常规追问链
                "常规追问",
                "补充实现细节、排障过程和验证方式"
        );
    }

    /**
     * 追问计划的不可变值对象。
     *
     * <p>使用 Java 16+ record 自动生成构造器、getter、equals、hashCode。
     * 这三个字段共同定义了"追问什么"和"怎么追问"。</p>
     *
     * @param chain     LiteFlow 规则链名称，决定本轮追问执行哪套策略流程
     * @param level     追问深度或策略等级（基础追问/常规追问/深度追问），用于数据库记录和前端展示
     * @param objective 追问要补充确认的具体目标说明，AI 生成追问内容时用它作为指引
     */
    public record FollowUpPlan(
            String chain,
            String level,
            String objective
    ) {
    }
}
