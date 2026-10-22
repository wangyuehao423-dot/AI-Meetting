package com.example.interview.application;

import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yomahub.liteflow.exception.JsonProcessException;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;

import org.apache.naming.java.javaURLContextFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

@Service 
public class JudgeTaskCompensationService {
    private final SessionSnapshotRepository sessionSnapshotRepository;

    private static final String QUEUE = "interview:task:judge";

    private static final int MAX_COMPENSATION_COUNT = 3;

    private final JdbcTemplate jdbc;

    private final StringRedisTemplate redis;

    private final ObjectMapper mapper;

    private final QuestionMapper questions;

    // 从这里开始是新增的：心跳服务，用来回答"这条卡住的答案，处理线程还活着吗"。
    private final HeartbeatService heartbeat;
    // 新增结束

    public JudgeTaskCompensationService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            // 题目 Mapper，用于 findSequenceNo 查询题号。
            QuestionMapper questions, SessionSnapshotRepository sessionSnapshotRepository,
            // 从这里开始是新增的：构造器参数末尾追加 HeartbeatService（按类型注入，
            // 追加在末尾不会打乱既有参数顺序）。
            HeartbeatService heartbeat
            // 新增结束
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
        this.questions = questions;
        this.sessionSnapshotRepository = sessionSnapshotRepository;
        // 从这里开始是新增的：
        this.heartbeat = heartbeat;
        // 新增结束
    }
     @Scheduled(fixedDelay = 30_000)
    public void compensateStuckAnswers() {
        // 执行 SQL 查询，查找需要补偿的答案记录。
        // SQL 条件说明：
        // 1. score is null：还未评分
        // 2. status <> 'JUDGE_FAILED'：未标记为"评分失败"
        //    （标记为失败的记录不再重复补偿，避免死循环）
        // 3. created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)：
        //    创建时间超过 5 分钟，确保不会扫到刚提交的正常答案
        // 4. order by created_at：先处理最老的卡住记录
        // 5. limit 100：每次最多处理 100 条，避免一次扫描过多导致数据库抖动
        List<PendingAnswer> pending =
                jdbc.query(
                        """
                        select id, session_id, question_id,
                               answer, retry_count, is_follow_up
                        from interview_answer
                        where score is null
                          and status <> 'JUDGE_FAILED'
                          and created_at <
                              DATE_SUB(NOW(), INTERVAL 5 MINUTE)
                        order by created_at
                        limit 100
                        """,
                    
                        (ResultSet resultSet, int rowNum) ->
                                new PendingAnswer(
                                        resultSet.getLong("id"),
                                        UUID.fromString(
                                                resultSet.getString("session_id")
                                        ),
                                        UUID.fromString(
                                                resultSet.getString("question_id")
                                        ),
                                        resultSet.getString("answer"),
                                        resultSet.getInt("retry_count"),
                                        // 从这里开始是新增的：读出追问标记，补偿重投时必须原样带回去，
                                        // 否则追问任务会被当成初次作答，错误地推进主线状态机。
                                        resultSet.getInt("is_follow_up")
                                        // 新增结束
                                )
                );

    
        for (PendingAnswer answer : pending) {
            compensate(answer);
        }
    }
    private record PendingAnswer(
            long id,              // interview_answer 表主键
            UUID sessionId,       // 面试会话 ID
            UUID questionId,      // 题目 ID
            String answer,        // 答案文本
            int retryCount,       // 当前重试次数
            // 从这里开始是新增的：追问标记（0=初次作答，1=追问作答）。
            // 用 int 承接数据库 TINYINT，构造 JudgeTask 时再转成 boolean，避免直接读 boolean 的映射问题。
            int isFollowUp
            // 新增结束
    ) {
    }
    private void compensate(PendingAnswer answer)
    {
        try
        {
            JudgeTask task = new JudgeTask(answer.sessionId(),
                                            answer.questionId(),
                                            answer.answer(),
                                        // 从这里开始是修改的：把读出的 is_follow_up 转成 boolean 带到队列载荷里。
                                        answer.isFollowUp() == 1,
                                        // 修改结束
                                        answer.retryCount());
            String raw = mapper.writeValueAsString(task);
            byte[] queueKey = QUEUE.getBytes(StandardCharsets.UTF_8);
            byte[] taskValue = raw.getBytes(StandardCharsets.UTF_8);

            Object queuePosition = redis.execute(
                    (RedisCallback<Object>) connecetion -> connecetion.execute(
                        "LPOS",
                        queueKey,
                        taskValue
                    ));
            // 从这里开始是修改的：原来只有一句 queuePosition != null 的判断，
            // 现在扩成"待处理队列里还有任务 或 处理线程心跳还在"才跳过。
            // 为什么必须补上心跳这一半：任务一旦被消费者取走，LPOS 立刻查不到；
            // 若线程随后崩溃，这条答案会一直被当成"正在评分"，永远等不到重投。
            boolean alive = heartbeat.exists(answer.sessionId());
            if(queuePosition != null || alive)
            {
                // 队列里还有任务 = 还没被人取走，正常；
                // 心跳还在 = 取走它的线程还活着，只是 AI 长尾比较慢，重投会造成同题双评。
                return;
            }
            // 修改结束
            int nextRetry = answer.retryCount() + 1;
            if (nextRetry > MAX_COMPENSATION_COUNT) {
                // 超过 3 次补偿上限，标记为评分失败后不再重试。以下“标记失败”逻辑
                // 只在此处使用一次，因此直接内联，不再单独抽取方法。
                // 更新答案表：标记为评分失败。WHERE 中的 score is null 是为了防并发——
                // 若补偿期间正常流程已评完分写入 score，这里就不应覆盖已评分的记录。
                jdbc.update(
                        """
                        update interview_answer
                        set retry_count = ?,
                            status = 'JUDGE_FAILED'
                        where id = ? and score is null
                        """,
                        nextRetry,
                        answer.id()
                );
                // 写入补偿日志，供运维排查失败原因；日志保留 retry_count，
                // 便于分析重试分布、优化补偿上限配置，日后重算也能追溯原始数据。
                jdbc.update(
                        """
                        insert into interview_judge_compensation_log
                            (answer_id, retry_count, reason)
                        values (?, ?, ?)
                        """,
                        answer.id(),
                        nextRetry,
                        "Redis 队列中找不到对应的评分任务，补偿多次后放弃"
                );
                return;
            }
            JudgeTask compensated = new JudgeTask(
                answer.sessionId(),
                answer.questionId(),
                answer.answer(),
                // 从这里开始是修改的：重投时同样带上追问标记，保证追问作答补偿后仍不推进主线。
                answer.isFollowUp() == 1,
                // 修改结束
                nextRetry
            );
            String conpensatedRaw = mapper.writeValueAsString(compensated);
            redis.opsForList().rightPush(QUEUE, conpensatedRaw);
            jdbc.update(
                    """
                    update interview_answer
                    set retry_count = ?,
                        status = 'PENDING_JUDGE'
                    where id = ? and score is null
                    """,
                    nextRetry,
                    answer.id()
            );
        }
        catch(JsonProcessingException e)
        {
            throw new IllegalStateException(
                "无法序列化补偿任务: answerId=" + answer.id(),
                    e
            );
        }
    }

    public int findSequenceNo(UUID questionId) {
        // 通过题目表的 MyBatis-Plus Mapper 按主键查询实体。
        // 原始样品遗漏了 questions 字段的注入，会导致这里无法编译，
        // 现已在字段声明与构造器补上 QuestionMapper 的注入。
        QuestionEntity question = questions.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getId,
                                questionId.toString()
                        )
        );
        // 查不到题目或题号为空视为异常，直接抛出 IllegalArgumentException。
        if (question == null
                || question.getSequenceNo() == null) {
            throw new IllegalArgumentException(
                    "未找到题目序号: questionId=" + questionId
            );
        }
        return question.getSequenceNo();
    }

}
