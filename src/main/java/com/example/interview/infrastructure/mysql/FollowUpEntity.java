package com.example.interview.infrastructure.mysql;

import java.time.LocalDateTime;

// 从这里开始是修改的：补 TableField 注解的 import。
// 为什么需要它：下面的 chain 字段在表里并不存在，必须显式告诉 MyBatis-Plus"这个字段不映射任何列"。
import com.baomidou.mybatisplus.annotation.TableField;
// 修改结束
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("interview_follow_up")
public class FollowUpEntity {
    private Long id;

    /** 面试会话 UUID，与 interview_session 表的 session_id 对应。 */
    private String sessionId;

    /** 题目 UUID，与 interview_question 表的 question_id 对应。 */
    private String questionId;

    /** 触发本次追问时的评分（0-100）。冗余字段，避免联表查询。 */
    private Integer score;

    /** 追问等级：基础追问 / 常规追问 / 深度追问。用于前端区分展示样式。 */
    private String level;

    // 从这里开始是修改的：链名只留在 LiteFlow 上下文里，不落库。
    // interview_follow_up 表里没有 chain 列（真实列是 id / session_id / question_id /
    // score / level / content / created_at），而 MyBatis-Plus 默认会把实体的每个字段
    // 都当成一列拼进 SQL。不标 exist = false 的话，selectOne / selectList 会生成
    // "SELECT ..., chain, ... FROM interview_follow_up"，执行时直接报 Unknown column 'chain'，
    // 追问的"先查后插"和报告汇总追问内容都会 500。
    @TableField(exist = false)
    private String chain;
    // 修改结束

    /** AI 生成的追问正文（Markdown 格式），前端直接渲染。 */
    private String content;

    /** 记录创建时间，由数据库自动写入，用于排序和展示。 */
    private LocalDateTime createdAt;

}