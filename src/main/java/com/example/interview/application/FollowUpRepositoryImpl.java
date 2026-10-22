package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.FollowUpEntity;
import com.example.interview.infrastructure.mysql.FollowUpMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// 追问表仓储的实现类，实现 application 层定义的 FollowUpRepository 接口。
// 必须显式 implements FollowUpRepository，否则：
// 1. 方法上的 @Override 找不到被覆盖的目标，编译报错；
// 2. Spring 容器里没有 FollowUpRepository 类型的 Bean，
//    PersistFollowUp 等按接口注入的组件无法启动。
@Repository 
public class FollowUpRepositoryImpl implements FollowUpRepository {
    private final FollowUpMapper followUps;

    public FollowUpRepositoryImpl(FollowUpMapper followUps) {
        this.followUps = followUps;
    }

    @Override 
    public void insertIfAbsent(UUID sessionId, UUID questionId, String content, String level)
    {
        FollowUpEntity existing = followUps.selectOne(
                Wrappers.<FollowUpEntity>lambdaQuery()
                        .eq(FollowUpEntity::getSessionId, sessionId.toString())
                        .eq(FollowUpEntity::getQuestionId, questionId.toString())
        );
         if (existing != null) {
            return;   // 已经追问过这道题了，直接跳过，不覆盖原记录
        }
        FollowUpEntity entity = new FollowUpEntity();
        entity.setSessionId(sessionId.toString());
        entity.setQuestionId(questionId.toString());
        entity.setContent(content);
        // 只写表里真实存在的列：interview_follow_up 没有 chain 列，
        // 追问等级存在 level 列（NOT NULL，必须赋值）；
        // LiteFlow 链名只留在 FollowUpExecution 上下文里，不入库。
        entity.setLevel(level);
        // 整个方法里唯一的写库动作；即便并发插入漏过上面的先查，也会被唯一索引兜住。
        followUps.insert(entity);
    }

    // 从这里开始是新增的：查询追问，供前端在"答完原题、取下一题之前"确认有没有追问要答。
    /**
     * 按 session_id + question_id 查一条追问，转成前端视图。
     *
     * <p>为什么用 selectOne 而不是 selectList：表上有唯一键
     * uk_follow_up_session_question (session_id, question_id)，同一道题的追问最多一条，
     * 用 selectOne 语义更准，也顺手把"真出现两条"这种异常数据暴露成异常而不是悄悄取第一条。</p>
     *
     * <p>为什么查不到返回 null 而不是抛异常：没有追问是完全正常的业务分支
     * （分数段决定不追问、或追问任务还在队列里没生成完），调用方要的就是
     * "没有追问"这个信息，不该当成错误。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 原题 ID
     * @return 追问视图；查不到时返回 null
     */
    @Override
    public FollowUpView find(UUID sessionId, UUID questionId)
    {
        FollowUpEntity entity = followUps.selectOne(
                Wrappers.<FollowUpEntity>lambdaQuery()
                        .eq(FollowUpEntity::getSessionId, sessionId.toString())
                        .eq(FollowUpEntity::getQuestionId, questionId.toString())
        );
        if (entity == null) {
            return null;
        }
        // 只挑前端要的三个字段：题号前端已经有了，这里回传是为了让调用方
        // 能直接把视图里的 questionId 当作提交追问答案的 questionId 用。
        return new FollowUpView(
                questionId,
                entity.getLevel(),
                entity.getContent()
        );
    }
    // 新增结束
}