package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * interview_report 表的 MyBatis-Plus 实体。
 *
 * <p>一场面试最终只生成一份报告。如果多次调用生成接口，
 * content 会被覆盖更新，但 session_id 保持不变。</p>
 */
@TableName("interview_report")
public class ReportEntity {

    /** 自增主键，无业务含义。 */
    private Long id;

    /** 面试会话 UUID。唯一约束保证一次面试只对应一份报告。 */
    private String sessionId;

    /** AI 生成的完整 Markdown 报告内容。可以为 null（AI 调用失败时的中间状态）。 */
    private String content;

    // 从这里开始是新增的：报告状态。为什么必须落到实体上：异步生成后前端不再等一个长连接，
    // 只能靠轮询状态判断"还要不要等"；状态不映射到实体，查询回来的永远是 null。
    /** 报告生成状态：GENERATING（生成中）/ COMPLETED（已完成）/ FAILED（生成失败）。 */
    private String status;
    // 新增结束

    /**
     * 最后一次状态或内容更新时间。
     *
     * <p>为什么需要这个字段？当报告是异步生成时（如放入消息队列），
     * 前端可以轮询该字段是否变化来判断报告是否已生成完毕。
     * 注意：该列已在 docker/mysql/01-schema.sql 中补上
     * （ALTER TABLE interview_report ADD COLUMN updated_at ...），
     * 旧库需要执行一次这段迁移，否则这个字段读回来永远是 null。</p>
     */
    private LocalDateTime updatedAt;

    /** 报告的首次创建时间，由数据库自动写入。 */
    private LocalDateTime createdAt;

    // ---- getters / setters ----

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }

    // 从这里开始是新增的：status 的读写方法。MyBatis-Plus 默认按方法名反解列名
    // （getStatus → status），少了这两个方法，状态列查出来也不会被赋进对象。
    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }
    // 新增结束

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}