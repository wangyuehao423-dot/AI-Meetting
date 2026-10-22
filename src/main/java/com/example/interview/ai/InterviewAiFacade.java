package com.example.interview.ai;

import com.example.interview.application.JudgeResult;
import com.example.interview.infrastructure.singleflight.RedisSingleflight;
import com.example.interview.skill.SkillDefinition;
import com.example.interview.skill.SkillRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class InterviewAiFacade {
    private final ChatClient chatClient;
    private final RedisSingleflight singleflight;
    // 从这里开始是新增的：业务 Skill 注册表。
    // 出题 / 评分 / 报告 / 追问四条 AI 调用各自的业务约束都以 Markdown 规则文件
    // 的形式放在 resources/skills 下，由 SkillRegistry 启动时统一加载；
    // 注入到门面后，四条链路才能通过 skillPrompt(name) 把这些规则拼进提示词。
    private final SkillRegistry skills;
    // 新增结束

    public InterviewAiFacade(
            RedisSingleflight singleflight,
            ChatClient.Builder chatClientBuilder,
            // 从这里开始是新增的：构造器末尾追加 SkillRegistry，
            // 追加在末尾不影响既有调用方（Spring 按类型注入，顺序无关）。
            SkillRegistry skills
            // 新增结束
    )
    {
        this.singleflight = singleflight;
        this.chatClient = chatClientBuilder.build();
        this.skills = skills;
    }

    /**
     * 按业务名称拼接对应的 Skill 定义，作为 AI 提示词的固定前缀。
     *
     * <p>这是全项目的"统一自动消费注入点"：出题 / 评分 / 报告 / 追问四条 AI 链路
     * 都只调这一个方法，谁都不各自拼 Skill。注入的内容直接读取结构化 SkillDefinition
     * （见 SkillLoader 解析的元数据），把"用途 + 输出契约 + 规则正文"一次性拼进提示词，
     * 因此 Skill 有了任何改动，四条链路下次调用自动生效，无需动一处调用代码。
     *
     * <p>名称拼错时 skills.get(name) 会抛 IllegalStateException("skill not found: " + name)，
     * 第一次调用就暴露，不会静默退化成"没有规则的提示词"。
     *
     * @param name SkillRegistry 中的技能名称，例如 "question-generation"
     * @return 包裹了技能定义、可直接拼入提示词的文本
     */
    private String skillPrompt(String name)
    {
        SkillDefinition definition = skills.get(name);
        // 元数据可能为空（兼容没有 front matter 的旧文件），为空的字段不拼进提示词。
        String purpose = blank(definition.purpose())
                ? ""
                : "用途：" + definition.purpose() + "\n";
        String output = blank(definition.outputContract())
                ? ""
                : "必须的输出契约：" + definition.outputContract() + "\n";
        return "【业务 Skill：" + name + "】\n"
                + purpose
                + output
                + definition.content()
                + "\n【业务 Skill 结束】\n";
    }

    // 判断文本是否为空或仅空白，用于决定某段元数据要不要拼进提示词。
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

private String excerpt(String text, int maxLength) {
    if (text == null) {
        return "";
    }
    return text.length() <= maxLength
            ? text
            : text.substring(0, maxLength);
}
private String chat(String prompt) {
        // 从这里开始是修改的：给 AI 调用加"瞬时失败自动重试"。
        // 为什么必须加这一层：出题 / 评分 / 报告 / 追问四条链路最终都走这一个方法，
        // 而它之前只调用一次、失败即抛。AI 上游偶发超时或返回空串时，就会直接变成 500
        // （表现就是"刚发起取第一题那次请求 500"，把整场面试开场打断）。
        // 这里只对两种"失败后重试大概率能自愈"的情况重试 3 次：
        //   1) TransientAiException —— Spring AI 给"网络抖动 / 上游 5xx"这类瞬时错误打的标签；
        //   2) AI 返回了空串。
        // 鉴权、请求非法这类 NonTransientAiException 不在此列，重试也是白费，放出去让上层处理。
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt += 1) {
            try {
                String content = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                if (content != null && !content.isBlank()) {
                    return content;
                }
                // 空返回视为瞬时失败：记下原因，短暂退避后重试下一次。
                lastFailure = new IllegalStateException(
                        "AI returned empty content"
                );
            } catch (RuntimeException retryableEx) {
                // 只重试"瞬时异常"或"上游限流"；其它异常原样向上抛。
                // 为什么不能只认 TransientAiException：部分版本里上游 429 限流会被
                // Spring AI 归类为 NonTransientAiException 直接抛出，但限流本质上和
                // 网络抖动一样属于"等一下再试大概率成功"的暂时性故障，不重试就会
                // 直接把整条链路打成 500（表现就是开场取题必挂）。
                if (!isRetryable(retryableEx)) {
                    throw retryableEx;
                }
                lastFailure = retryableEx;
            }
            if (attempt < 2) {
                try {
                    // 退避递增：1/2 次后分别等 600ms / 1200ms，给上游留出恢复时间。
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    // 线程被中断：不再重试，把站到的最新失败原因抛回去。
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException(
                        "AI call failed unexpectedly"
                )
                : lastFailure;
    }
    // 判断一次失败是否值得重试：Spring AI 的瞬时异常标签，或限流(429)等
    // 上游暂时性故障。沿整条 cause 链检查，因为 429 常被上层包装进自定义异常。
    private boolean isRetryable(RuntimeException failure) {
        if (failure instanceof TransientAiException) {
            return true;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof org.springframework.web.client.HttpStatusCodeException httpEx
                    && httpEx.getStatusCode().value() == 429) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429")
                        || lower.contains("rate limit")
                        || lower.contains("too many requests")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
private JsonNode parseJson(String content) {
        try {
            return new ObjectMapper().readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "AI returned invalid JSON",
                    e
            );
        }
    }
private JudgeResult parseJudge(String content, long fencingToken) {
        // 纯解析：把已被校验为合法结构的评分 JSON 转成 JudgeResult。
        // 格式校验统一在 chatJudge 的重试循环里完成，这里不再抛异常。
        JsonNode node = parseJson(content);
        return new JudgeResult(
                node.path("score").asDouble(),
                node.path("feedback").asText(""),
                fencingToken,
                false
        );
    }
private String chatJudge(UUID sessionId, UUID questionId, String answer,
                         // 从这里开始是修改的：新增 followUp 参数，用于在提示词里区分作答类型。
                         boolean followUp) {
                         // 修改结束
        // 调用 AI 生成评分并做格式自纠错的入口。
        // 若 AI 返回的内容缺 score/feedback 或不是合法 JSON，不直接抛异常，
        // 而是把具体问题反馈给 AI，让它按正确格式重新生成，最多纠正 2 次；
        // 反复不满足时才抛出异常，避免无限循环。
        // 从这里开始是修改的：提示词起点先拼上评分 Skill（interview-scoring），
        // 让 AI 先读到"评分维度、分数档位、JSON 结构"这些业务规则，再执行评分指令。
        // 另外按 followUp 追加一句区分说明：追问作答要结合原题评分与追问目标综合评估，
        // 初次作答保持原提示词不变，避免影响既有评分口径。
        String prompt = skillPrompt("interview-scoring")
                + "你是严格的技术面试官。"
                + "请用 JSON 返回 score(0-100) 和 feedback。"
                + "按正确性、完整性、深度、表达综合评分。"
                + (followUp
                        ? "这是对同一道题的追问回答，请结合原题评分与追问目标综合评估。"
                        : "")
                + "题目ID=" + questionId
                + "，候选人回答=" + answer;
        // 修改结束
        String content = chat(prompt);
        for (int attempt = 0; attempt < 2; attempt++) {
            String issue = judgeFormatIssue(content);
            if (issue == null) {
                return content;
            }
            content = chat("上次输出不满足要求：" + issue
                    + "\n请仅返回 JSON："
                    + "{\"score\":0-100,\"feedback\":\"评语\"}"
                    + "，不要输出其他内容。\nAI 上次输出：\n"
                    + content);
        }
        throw new IllegalStateException(
                "AI 多次输出仍不符合评分格式: sessionId="
                        + sessionId + ", questionId=" + questionId
        );
    }
private String judgeFormatIssue(String content) {
        // 检查 AI 输出是否为合法评分 JSON。合法返回 null；
        // 否则返回能够指导 AI 修正的具体问题描述，
        // 供 chatJudge 拼接到纠正 prompt 里反馈给 AI。
        try {
            JsonNode node = new ObjectMapper().readTree(content);
            if (node.path("score").isMissingNode()) {
                return "缺少 score 字段（0-100 的数值）";
            }
            if (node.path("feedback").asText("").isBlank()) {
                return "缺少 feedback 字段（非空评语）";
            }
            return null;
        } catch (JsonProcessingException e) {
            return "输出不是合法 JSON";
        }
    }
public GeneratedQuestion generateQuestion(UUID sessionId,
            String positionName,
            String positionDescription,
            String requiredSkills,
            String resumeContent,
            int sequenceNo)
    {
    // 从这里开始是修改的：原来这一行是 var prompt = ""（后面挂着被注释掉的 skillPrompt），
    // 现在恢复成真实调用，把出题 Skill（question-generation）拼在提示词最前面。
    // 紧跟其后的 + String.format(...) 一个字不动。
    var prompt = skillPrompt("question-generation")
                + String.format(
                "你是一位中文技术面试官。"
                        + "请根据候选人的真实简历和目标岗位，"
                        + "为第 %d 题生成一道技术面试题。\n"
                        + "目标岗位：%s\n"
                        + "岗位说明：%s\n"
                        + "岗位技能要求：%s\n"
                        + "候选人简历：\n%s\n"
                        + "要求：题目必须围绕简历中明确出现的"
                        + "项目、职责或技术栈，不要编造经历。"
                        + "只返回 JSON："
                        + "{\"content\":\"题目正文\","
                        + "\"category\":\"考察分类\"}",
                sequenceNo,
                positionName,
                positionDescription,
                requiredSkills,
                excerpt(resumeContent, 6000)
        );
    // 修改结束
    // 从这里开始是修改的：把"出题"接入 RedisSingleflight，凑齐 评分/追问/出题 三条去重链路。
    // 为什么必须接：之前 judge（评分）和 FollowUpGenerationService（追问）都走了
    // singleflight.executeFenced，只有出题仍是直接调 chat——并发触发相同 sessionId+题号的
    // 请求会各自打一次 AI，浪费且可能产出不一致的题。key 用「question:sessionId:sequenceNo」
    // 就把这些重复请求合并成一个，代价是出题结果缓存在 Redis，后续取值走 Fencing Token 校验。
    var questionKey = "question:" + sessionId + ":" + sequenceNo;
    var result = singleflight.executeFenced(
            questionKey,
            Duration.ofMinutes(5),
            // 生产函数只负责真正调 AI 出题，返回的是"未解析的原始 JSON 正文"，
            // 去重、幂等、围栏令牌都由单飞框架负责，这里不重复实现。
            () -> chat(prompt)
    );
    // Fencing Token 校验：出题结果落到 Redis 后有 5 分钟窗口，若这期间同一 key
    // 又被重触发，旧结果会被新结果顶掉。本轮拿到的 token 若已不是"当前最新"，
    // 说明这次读到的是过期缓存，直接拒绝，避免旧题覆盖新题的边界竞态。
    if (!singleflight.acceptFence(questionKey, result.token())) {
        throw new IllegalStateException(
                "拒绝过期出题结果: sessionId=" + sessionId
                        + ", sequenceNo=" + sequenceNo
        );
    }
    var node = parseJson(result.value());
    var content = node.path("content").asText().trim();
    var category = node.path("category").asText("简历定制").trim();
    if(content.isBlank())
    {
        throw new IllegalStateException("AI returned an empty interview question");

    }
    return new GeneratedQuestion(
                content,
                category
        );
    }
/**
 * 按指定追问深度等级、原题、作答和追问口径生成追问正文。
 *
 * 与 generateFollowUp 的区别：多接收 level、questionTitle、answer、directive
 * 四个参数。供三个分数段生成节点（LoadQuestionContext 先填充 questionTitle/answer，
 * 再由 GenerateBasicFollowUp / GenerateNormalFollowUp / GenerateDeepFollowUp）
 * 在 process() 中按各自的链等级与口径调用。questionTitle/answer 让 AI 真正围绕
 * 候选人答过的内容发问，directive 则把三个节点各自的追问侧重说清楚，
 * 避免 AI 把概念题问成大而空的泛化问题。
 *
 * @param sessionId   当前面试会话 ID，用于 AI 追溯上下文
 * @param questionId  当前题目 ID，用于 AI 定位追问对象
 * @param score       本题评分（0-100），低分补基础、高分挖深度
 * @param level       追问深度等级：basic（基础追问）/ normal（常规追问）/ deep（深度追问）
 * @param objective   追问的具体目标说明，指引 AI 不偏离方向
 * @param questionTitle  原题正文，追问必须围绕的原题内容
 * @param answer      候选人作答，追问针对的实际答面
 * @param directive   本分数段的追问口径（节点自带），控制追问侧重与语气
 * @return 纯文本的中文追问正文，不包含 JSON 包裹或额外解释
 */
public String generateFollowUpText(
        UUID sessionId,
        UUID questionId,
        int score,
        String level,
        String objective,
        String questionTitle,
        String answer,
        String directive
) {
    // 从这里开始是修改的：先拼追问 Skill（follow-up-decision）再调模型，
    // 让 AI 按规则文件里的追问策略与口径发问；结尾的 .trim() 保留。
    return chat(skillPrompt("follow-up-decision") + "\n" + """
            你是一名技术面试官。
            请根据评分、追问深度等级、追问目标、原题、作答和追问口径生成一道中文追问。
            只返回追问正文，不要返回 JSON，不要附加解释。
            sessionId：%s
            questionId：%s
            score：%s
            level：%s
            objective：%s
            原题：%s
            候选人作答：%s
            追问口径：%s
            """.formatted(
            sessionId,
            questionId,
            score,
            level,
            objective,
            questionTitle,
            answer,
            directive
    )).trim();
    // 修改结束
}

    // 从这里开始是新增的：按"追问计划"生成追问正文的通用入口。
    // 参数类型直接写 FollowUpDecisionService.FollowUpPlan：本类与决策服务同在 ai 包下，
    // 不用额外 import，写全名也让"这个计划从哪来"一目了然。
    /**
     * 生成追问内容。
     *
     * 为什么不做去重与围栏：去重（Singleflight）和防过期（Fencing Token）由追问链的入口
     * FollowUpGenerationService.executeFollowUpWithSingleflight 统一负责，门面只做
     * "拼提示词 → 调模型 → 校验非空"这一件事，职责不重叠。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param score      当前题的评分，模型据此拿捏追问强度
     * @param plan       追问计划（链名、等级、追问目标）
     * @return 生成的追问正文，纯文本，不含 JSON 包裹
     */
    public String generateFollowUp(
            UUID sessionId,
            UUID questionId,
            double score,
            FollowUpDecisionService.FollowUpPlan plan
    ) {
        // 用文本块把计划拼进提示词：chain 让模型知道本题走哪套策略，objective 是不跑偏的锚点；
        // 明确"只返回正文"，省掉出库前再剥一层 JSON 的解析代码。
        return chat("""
                你是一名技术面试官。
                请根据评分和追问计划生成一道中文追问。
                只返回追问正文，不要返回 JSON，不要附加解释。
                sessionId：%s
                questionId：%s
                score：%s
                chain：%s
                objective：%s
                """.formatted(
                sessionId,
                questionId,
                score,
                plan.chain(),       // 追问策略链名称，与 interview.xml 的 <chain name> 一致
                plan.objective()    // 字段名是 objective 不是 target，写错编译不过
        )).trim();   // 抹掉首尾空白，避免把换行/空格当成追问正文存进库
    }
    // 新增结束

public JudgeResult judge(UUID sessionId, UUID questionId, String answer,
                         // 从这里开始是修改的：新增 followUp 参数，透传给 chatJudge 决定评分提示词。
                         boolean followUp)
                         // 修改结束
    {
        // 用同一会话内相同 key 合并重复评分请求，并生成围栏令牌防止旧结果覆盖新结果。
        // 从这里开始是修改的：key 里带上 followUp，避免"初次评分"和"追问评分"两个请求
        // 因为 sessionId+questionId+answer 相同而被 Singleflight 合并成同一个结果。
        var key = "judge:" + sessionId + ":" + questionId
                + ":" + answer.hashCode()
                + ":" + followUp;
        // 修改结束
        var result = singleflight.executeFenced(
                key,
                Duration.ofMinutes(2),
                // 生产者里内置格式自纠错：AI 输出不合规时会把问题反馈给它重新生成。
                // 从这里开始是修改的：把 followUp 一并透传给 chatJudge。
                () -> chatJudge(sessionId, questionId, answer, followUp)
                // 修改结束
        );
        if (!singleflight.acceptFence(key, result.token())) {
            throw new IllegalStateException(
                    "拒绝过期评分结果: sessionId=" + sessionId
                            + ", questionId=" + questionId
            );
        }
        // 到这里 content 已经过 chatJudge 的格式校验，parseJudge 只负责纯解析。
        return parseJudge(result.value(), result.token());
    }

    // ============================================================
    // 方法：generateReport —— 根据整场面试数据生成 Markdown 总结报告
    // ============================================================
    // 目的：把本场面试的完整数据（题目、回答、评分、反馈、追问）交给大模型，
    //      由它输出一份可直接展示和落库的 Markdown 报告，用于面试结束后
    //      给候选人/面试官复盘。
    //
    // 参数：
    //   sessionId —— 面试会话 ID，拼入提示词便于 AI 理解上下文
    //   context   —— 本场面试的完整数据文本，通常由
    //                InterviewReportDataRepository.loadContext 组装而来
    //
    // 返回值：AI 生成的 Markdown 报告正文（包含总体评价、逐题点评、
    //        能力维度分析、改进建议四个章节）
    //
    // 设计说明：
    //   - 直接让 AI 产出 Markdown，而不是返回结构化 JSON 再由后端拼模板：
    //     报告的版式本来就适合由模型自由发挥，后端只负责存储和展示，
    //     这样调整报告版式时只需要改提示词，不用改代码。
    //   - 统一通过 chat 方法发送请求，因此空返回等异常校验与其它 AI 调用一致。
    //   - context 可能很长，用 excerpt 截断，避免超出模型输入长度限制。
    // ============================================================
    public String generateReport(UUID sessionId, String context) {
        // 从这里开始是修改的：原来是直接 return chat("""...""")，
        // 现在先把报告 Skill（report-review）拼在文本块前面，再交给 chat。
        return chat(skillPrompt("report-review") + "\n" + """
                你是一名资深技术面试官，需要为一场已经结束的面试撰写总结报告。
                请只依据给出的面试数据作出判断，不要编造数据中不存在的经历或结论。
                必须使用 Markdown 格式输出，并严格包含以下四个章节：
                ## 一、总体评价
                ## 二、逐题点评
                ## 三、能力维度分析
                ## 四、改进建议
                只返回 Markdown 正文，不要返回 JSON，不要附加额外解释。
                sessionId：%s
                面试数据：
                %s
                """.formatted(sessionId, excerpt(context, 12000)));
        // 修改结束
    }

    // ============================================================
    // 方法：analyzeDemeanor —— 组装表现分析提示词并调用 AI
    // ============================================================
    // 目的：向大模型发送一段面试回答文本，请求分析其中的语言表达表现。
    //      返回的是原始 JSON 字符串，而不是结构化对象——这样调用方可以
    //      选择缓存原始 JSON 还是缓存结构化对象。
    //
    // 参数：
    //   sessionId  —— 面试会话 ID，拼入提示词便于 AI 理解上下文
    //   transcript —— 语音转写或文本回答内容，是分析的主体
    //
    // 返回值：AI 返回的原始 JSON 字符串（格式见下方提示词中的 JSON 结构）
    //
    // 注意：提示词中明确要求 AI "只分析语言表达表现，不评价技术正确性"，
    //       并禁止编造语音、视频、表情或肢体动作信息——因为 AI 有时会
    //       幻觉式地添加"候选人的表情很自信"等无法从纯文本推断的信息。
    // ============================================================
    public String analyzeDemeanor(UUID sessionId, String transcript) {
        // 调用本类的 chat 方法（通用 AI 通信方法），
        // 传入专门为表现分析设计的提示词。
        // 提示词使用 Java 15 的 text block（"""..."""）编写，
        // 避免字符串拼接产生的转义地狱。
        return chat("""
                你是一名面试表达分析助手。
                请只分析候选人的语言表达表现，不评价技术正确性。
                不要编造语音、视频、表情或肢体动作信息。
                请返回严格的 JSON 对象，不要返回 Markdown 代码围栏或额外解释。
                JSON 必须包含以下字段：
                {
                  "confidence": 0,
                  "fillerCount": 0,
                  "transcriptLength": 0,
                  "clarityScore": 0,
                  "pace": "normal",
                  "feedback": ""
                }
                sessionId：%s
                回答文本：
                %s
                """.formatted(sessionId, transcript));
    }

    // ============================================================
    // 方法：parseDemeanor —— 解析 AI 返回的表现分析 JSON
    // ============================================================
    // 目的：将 AI 返回的原始 JSON 字符串解析成强类型的 DemeanorAnalysis 对象。
    //      使用 parseJson（本类中定义的通用 JSON 解析方法）先转为 JsonNode，
    //      然后逐字段提取值。每个字段都提供默认值，防止 AI 返回的 JSON 缺少字段
    //      导致整个流程崩溃。
    //
    // 参数：
    //   rawJson          —— AI 返回的原始 JSON 字符串
    //   transcriptLength —— 转写文本的实际长度（由调用方传入，比 AI 计算的更准确）
    //
    // 返回值：DemeanorAnalysis 结构化对象
    //
    // 容错设计：
    //   - pace 字段缺失时默认 "normal"（最安全的语速分类）
    //   - feedback 字段缺失时默认空字符串
    //   - 数值字段使用 Jackson 的 path().asXxx() 方法，即使路径不存在也返回 0
    // ============================================================
    public DemeanorAnalysisService.DemeanorAnalysis parseDemeanor(
            String rawJson,
            int transcriptLength
    ) {
        // 使用 Jackson 的 ObjectMapper（在本类中配置）将 JSON 解析为树模型
        var node = parseJson(rawJson);

        // 逐字段提取：path("字段名") 即使路径不存在也不会抛异常，
        // 后续的 asDouble() / asInt() / asText() 返回默认值。
        return new DemeanorAnalysisService.DemeanorAnalysis(
                node.path("confidence").asDouble(),    // 自信度，默认 0.0
                node.path("fillerCount").asInt(),       // 口头禅次数，默认 0
                transcriptLength,                       // 转写长度（由服务端传入，保证准确）
                node.path("clarityScore").asDouble(),   // 清晰度，默认 0.0
                node.path("pace").asText("normal"),     // 语速，默认 "normal"
                node.path("feedback").asText("")        // 反馈文本，默认 ""
        );
    }
}
