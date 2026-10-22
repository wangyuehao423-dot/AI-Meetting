package com.example.interview.infrastructure.mysql;

import java.util.UUID;

public class FollowUpExecution {

    /** 面试会话 ID，贯穿题目、答案、评分和报告的全局标识。规则链各节点用它来关联数据。 */
    private UUID sessionId;

    /** 当前正在追问的题目 ID。提交答案和评分时用它定位题目，追问节点据此判断补问方向。 */
    private UUID questionId;

    /** 候选人刚刚提交的答案文本。追问节点根据答案中的薄弱点决定从哪个方向追问。 */
    private String answer;

    /** 原题正文。由链首公共节点 loadQuestionContext 从题目表加载，供 AI 生成追问时围绕原题发问。 */
    private String questionTitle;

    /** 本次追问命中的规则链名称（followUpBasicChain 等）。由入口方法从 FollowUpPlan 填充，供日志和排查使用（不入库）。 */
    private String chain;

    /** 本题的评分（0-100）。策略选择节点根据它决定选择哪条追问链。 */
    private int score;

    /** 追问深度或策略等级（基础追问 / 常规追问 / 深度追问）。用于 AI 提示词中的语气和深度控制。 */
    private String level;

    /** 追问的具体目标描述。AI 生成追问内容时用这个字段作为指引，保证追问不偏离方向。 */
    private String objective;

    /** 题目原文、答案、追问内容或报告正文的通用容器。链上最后一个节点将其持久化到数据库。 */
    private String content;

    /**
     * 无参构造器：供「上下文构建节点」使用。
     * 节点从外部请求中拿到数据后，通过各字段的 setter 依次填充。
     */
    public FollowUpExecution() {
    }

    /**
     * 便捷构造器：直接传入本次追问最核心的四个参数。
     * 适用于调用方已经确定会话、题目、评分和追问目标的场景
     * （例如 FollowUpGenerationService.generateByFlow），
     * 无需先 new 再逐个 set。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param score      本题评分（0-100）
     * @param objective  追问目标说明
     */
    public FollowUpExecution(
            UUID sessionId,
            UUID questionId,
            int score,
            String objective
    ) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.score = score;
        this.objective = objective;
    }

    public UUID getSessionId() { return sessionId; }

    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getQuestionId() { return questionId; }

    public void setQuestionId(UUID questionId) { this.questionId = questionId; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public String getQuestionTitle() { return questionTitle; }

    public void setQuestionTitle(String questionTitle) { this.questionTitle = questionTitle; }

    public String getChain() { return chain; }

    public void setChain(String chain) { this.chain = chain; }

    public int getScore() { return score; }

    public void setScore(int score) { this.score = score; }

    public String getLevel() { return level; }

    public void setLevel(String level) { this.level = level; }

    public String getObjective() { return objective; }

    public void setObjective(String objective) { this.objective = objective; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
