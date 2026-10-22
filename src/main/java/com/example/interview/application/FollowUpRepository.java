package com.example.interview.application;

import java.util.UUID;

/**
 * 追问表仓储接口。
 *
 * 为什么定义在 application 层而不是 infrastructure 层：
 * 这是业务层的接口定义，具体实现在 infrastructure 层。
 * 遵循依赖倒置原则。
 */
public interface FollowUpRepository {

    /**
     * 如果不存在则插入追问记录。
     *
     * 幂等性保证：同一个 sessionId + questionId 只插入一次。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param content    追问内容
     * @param level      追问等级（基础追问 / 常规追问 / 深度追问），
     *                   对应 interview_follow_up 表的 level 列（NOT NULL），
     *                   必须传值，否则插入会被数据库的非空约束拒绝
     */
    void insertIfAbsent(
            UUID sessionId,
            UUID questionId,
            String content,
            String level
    );

    // 从这里开始是新增的：按会话 + 原题查一条追问。
    /**
     * 查询某一道原题上已经生成的追问。
     *
     * <p>为什么需要这个方法：追问是异步生成的，前端提交完原题答案、等到状态回到
     * QUESTIONING 之后，必须先确认"刚才这道题到底有没有追问"，才能决定是继续答追问
     * （提交时带 followUp=true）还是直接取下一题。没有这个查询，追问在界面上永远走不到。</p>
     *
     * <p>为什么返回视图对象而不是实体：实体属于 infrastructure 层，application 层
     * 的接口不该把 infrastructure 类型暴露出去；而且前端只需要 questionId / level / content
     * 三个字段，返回整行实体等于多传了 id、score、createdAt 等无用数据。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 原题 ID（追问记录就是用 session_id + question_id 关联原题的）
     * @return 追问视图；这道题没有追问时返回 null
     */
    FollowUpView find(UUID sessionId, UUID questionId);
    // 新增结束
}
