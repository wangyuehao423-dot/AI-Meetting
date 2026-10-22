package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

// 一次面试题答案及其异步评分状态。
// 对应 interview_answer 表，包含答案正文、评分结果和补偿控制字段。
@TableName("interview_answer")
public class AnswerEntity {
    // 数据库自增主键。
    private Long id;
    // 面试会话 ID，关联到具体的面试场次。
    private String sessionId;
    // 题目 ID，关联到面试题表。
    private String questionId;
    // 客户端生成的请求 ID，用于幂等去重，相同的 requestId 不会重复插入。
    private String requestId;
    // 候选人提交的答案正文。
    private String answer;
    // AI 评分结果，null 表示尚未评分。
    private Double score;
    // AI 评分反馈，包含对答案的评语和改进建议。
    private String feedback;
    // 围栏令牌，用于防止旧评分结果覆盖新评分结果。
    private Long fencingToken;
    // 答案创建时间，用于补偿服务判断评分是否超时。
    private LocalDateTime createdAt;
    // 补偿重试次数，每次补偿服务扫描处理时递增。
    private Integer retryCount;
    // 从这里开始是新增的：追问作答标记，对应 interview_answer.is_follow_up 列。
    // 为什么用 Integer 而不是 boolean：MyBatis-Plus 把基本类型 boolean 映射到 TINYINT 时
    // 存在类型处理器兼容问题（false 有时被写成 null 或读取报错），用包装类型 Integer
    // 直接承载 0/1，映射最稳妥。
    // 语义：0=初次作答（候选人第一次回答原题），1=追问作答（候选人回答的是原题派生出的追问）。
    // 追问不作为新题插入 interview_question，前端提交的 questionId 仍是原题 ID，
    // 所以必须靠这个标记把"同一道题的初次评分"和"追问评分"区分成两行，避免互相覆盖。
    private Integer isFollowUp;
    // 新增结束
    // 答案状态：PENDING_JUDGE（待评分）、JUDGED（已评分）、JUDGE_FAILED（评分失败）。
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    // 从这里开始是新增的：isFollowUp 的读写方法，供仓储层写入与补偿服务读取。
    public Integer getIsFollowUp() {
        return isFollowUp;
    }

    public void setIsFollowUp(Integer isFollowUp) {
        this.isFollowUp = isFollowUp;
    }
    // 新增结束

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}