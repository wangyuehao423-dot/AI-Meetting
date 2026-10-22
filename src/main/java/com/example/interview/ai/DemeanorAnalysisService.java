package com.example.interview.ai;

import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// 表现分析服务
// ============================================================
// 职责：只分析语言表达表现，不判断技术答案是否正确。
//       核心逻辑是"组装输入 -> 调 AI -> 解析输出"三步，
//       具体 AI 通信细节委托给 InterviewAiFacade。
// ============================================================
@Service  // 声明为 Spring Bean，自动被依赖注入扫描注册
public class DemeanorAnalysisService {

    // ============================================================
    // 表现分析结果的数据结构（Java 14+ record，不可变对象）
    // ============================================================
    // 说明：这是一个内部 record，定义在 DemeanorAnalysisService 内部，
    //       因为它只在此服务的上下文中使用，不暴露给外部模块。
    //       外部模块通过 getter 方法读取各字段，不能修改。
    //
    // 各字段详细说明：
    //   confidence      —— 自信度评分，0.0~1.0，越高越自信
    //   fillerCount     —— 口头禅/填充词出现次数（如"嗯""啊""那个"）
    //   transcriptLength—— 转写文本的实际字符长度（由服务端传入，确保准确）
    //   clarityScore    —— 表达清晰度评分，0.0~1.0，越高越清晰
    //   pace            —— 语速评价，枚举值：slow / normal / fast
    //   feedback        —— AI 给出的文本改进建议（自然语言描述）
    // ============================================================
    public record DemeanorAnalysis(
            double confidence,      // 自信度（0.0 ~ 1.0）
            int fillerCount,        // 填充词/口头禅出现次数
            int transcriptLength,   // 转写文本长度（服务端统计，保证准确）
            double clarityScore,    // 表达清晰度（0.0 ~ 1.0）
            String pace,            // 语速分类："slow" / "normal" / "fast"
            String feedback         // 改进建议（自然语言文本）
    ) {
    }

    // AI 门面对象：负责拼提示词、调 AI、解析响应
    // 通过构造器注入（Spring 自动注入，无需 @Autowired）
    private final InterviewAiFacade ai;

    /**
     * 构造器注入 InterviewAiFacade。
     * Spring 会自动从容器中查找 InterviewAiFacade 实例并传入。
     *
     * @param ai AI 门面对象，负责所有与大模型交互的细节
     */
    public DemeanorAnalysisService(InterviewAiFacade ai) {
        this.ai = ai;
    }

    /**
     * 分析指定会话中某段回答的语言表达表现。
     *
     * @param sessionId  面试会话 ID（用于日志追踪和缓存键生成）
     * @param transcript 语音转写或文本回答内容（不能为空或空白）
     * @return DemeanorAnalysis 结构化对象，包含语速、口头禅、清晰度等维度的分析结果
     * @throws IllegalArgumentException 如果 transcript 为空或全空白字符
     */
    public DemeanorAnalysis analyze(UUID sessionId, String transcript) {
        // ---- 步骤1：校验输入 ----
        // 如果 transcript 为 null 或全部是空白字符（空格、换行、Tab），
        // 直接抛异常终止，避免浪费一次 AI 调用。
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("transcript is empty");
        }

        // ---- 步骤2：调用 AI 获取原始 JSON ----
        // 先通过 Facade 组装提示词并发起 AI 请求，
        // 拿到 AI 返回的原始 JSON 字符串。
        // 为什么不在这里直接解析？因为原始 JSON 可能还需要在其他地方复用
        // （例如缓存原始 JSON 而非结构化对象），所以把"请求"和"解析"拆成两步。
        String rawJson = ai.analyzeDemeanor(sessionId, transcript);

        // ---- 步骤3：解析原始 JSON 为结构化对象 ----
        // 把 AI 返回的 JSON 字符串解析成强类型的 DemeanorAnalysis record。
        // transcript.length() 作为实际转写长度传入，因为 AI 返回的 JSON 中
        // 可能不包含这个字段，或者计算方式不一致，我们以服务端为准。
        return ai.parseDemeanor(rawJson, transcript.length());
    }

    /**
     * 把一次表现分析结构化成一段 Markdown，供报告页直接展示。
     *
     * <p>为什么要放在这里而不是报告服务里：表现分析的展示形态和数据结构强绑定
     * （confidence / clarityScore 是 0~1 的比例、pace 是枚举、feedback 是纯文本），
     * 收敛在产出这个数据结构的类型上最内聚；报告服务只需要拼接，不必知道这些字段各自的含义。
     *
     * @param analysis 一次已算好的表现分析结果
     * @return 可直接拼进报告 Markdown 的一段章节
     */
    public String toMarkdown(DemeanorAnalysis analysis) {
        return """
                ### 面试表现在线分析

                > 该分析由 AI 基于整场作答文本自动生成，用于复盘语言表达，不评判技术答案正误。

                - 自信度：%.0f%%
                - 表达清晰度：%.0f%%
                - 口头禅 / 填充词出现次数：%d 次
                - 语速：%s

                **AI 改进建议：**

                > %s
                """.formatted(
                analysis.confidence() * 100,
                analysis.clarityScore() * 100,
                analysis.fillerCount(),
                paceName(analysis.pace()),
                analysis.feedback()
        );
    }

    /** 把语速枚举值翻译成中文展示，未知值统一回退为"适中"。 */
    private String paceName(String pace) {
        if (pace == null) {
            return "未判断";
        }
        return switch (pace) {
            case "slow" -> "偏慢";
            case "fast" -> "偏快";
            default -> "适中";
        };
    }
}