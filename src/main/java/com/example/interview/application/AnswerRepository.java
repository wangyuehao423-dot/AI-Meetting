package com.example.interview.application;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.QuestionMapper;
// 从这里开始是新增的：补 QuestionEntity 的 import，findSequenceNo 要按主键查题目实体。
import com.example.interview.infrastructure.mysql.QuestionEntity;
// 新增结束
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.stream.Collectors;

@Repository 
public class AnswerRepository {
    // 答案表的 MyBatis-Plus Mapper，用于执行数据库操作。
    private final AnswerEntityMapper mapper;
    // 题目表的 MyBatis-Plus Mapper，用于查询题目是否存在。
    private final QuestionMapper questions;

    public AnswerRepository(
            AnswerEntityMapper mapper,
            QuestionMapper questions
    ) {
        this.mapper = mapper;
        this.questions = questions;
    }
    public boolean insertIfAbsent(AnswerCommand command) {
        AnswerEntity entity = new AnswerEntity();
        entity.setSessionId(command.sessionId().toString());
        entity.setQuestionId(command.questionId().toString());
        entity.setRequestId(command.requestId());
        entity.setAnswer(command.answer());
        // 从这里开始是新增的：把追问标记落到 is_follow_up 列（0=初次作答，1=追问作答）。
        // 唯一键已改为 (question_id, is_follow_up)，所以同一道题的初次作答与追问作答
        // 会各占一行；不写这个标记的话，追问答案会和初次答案撞唯一键被幂等丢弃。
        entity.setIsFollowUp(command.followUp() ? 1 : 0);
        // 新增结束

        try {
            return mapper.insert(entity) == 1;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }
    public StoredJudge findJudge(
            UUID sessionId,
            UUID questionId,
            // 从这里开始是修改的：新增 followUp 参数，用于定位是"初次评分"还是"追问评分"那一行。
            // 同一道题现在会有两行（is_follow_up=0 和 =1），不加这个条件会读到错误的一行。
            boolean followUp
            // 修改结束
    ) {
        AnswerEntity entity = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        AnswerEntity
                        >()
                        .eq("session_id", sessionId.toString())
                        .eq("question_id", questionId.toString())
                        // 从这里开始是新增的：按追问标记过滤，确保回放的是同一种作答的评分。
                        .eq("is_follow_up", followUp ? 1 : 0)
                        // 新增结束
                        .isNotNull("score")
                        .last("limit 1")
        );
        return entity == null
                ? null
                : new StoredJudge(entity.getScore(),
                 entity.getFeedback(), 
                 entity.getFencingToken());
                
    }
    public void recordJudge(
            UUID sessionId,
            UUID questionId,
            JudgeResult result,
            // 从这里开始是修改的：新增 followUp 参数，决定评分写回哪一行。
            // 追问评分必须写回 is_follow_up=1 的那一行，绝不能覆盖初次评分，
            // 否则候选人的首次得分会被追问得分冲掉。
            boolean followUp
            // 修改结束
    ) {
        // 按会话+题目定位答案记录，写入评分结果并推进状态。
        // 查不到记录视为异常，避免把评分写错到不存在的答案上。
        AnswerEntity entity = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        AnswerEntity
                        >()
                        .eq("session_id", sessionId.toString())
                        .eq("question_id", questionId.toString())
                        // 从这里开始是新增的：按追问标记过滤，保证只更新对应类型的作答行。
                        .eq("is_follow_up", followUp ? 1 : 0)
                        // 新增结束
                        .last("limit 1")
        );
        if (entity == null) {
            throw new IllegalStateException(
                    "answer not found"
            );
        }
        entity.setScore(result.score());
        entity.setFeedback(result.feedback());
        // 评分成功后更新状态为 JUDGED，避免下一轮补偿服务继续扫描该记录。
        entity.setStatus("JUDGED");
        mapper.updateById(entity);
    }

    // 从这里开始是新增的：按题目 UUID 查题号。
    /**
     * 按题目 UUID 查出它的 sequence_no。
     *
     * <p>谁在用：AnswerCommandService 提交完答案后要算"下一题序号 = 当前题序号 + 1"，
     * 而 command 里只有 questionId，没有题号。FollowUpGenerationService 那条路径是
     * 另按"会话内最大 sequence_no"直接算的（追问时并不知道具体哪道题），两条路径互不影响。
     *
     * <p>为什么查不到直接抛异常，而不是返回 0：题号从 1 开始，0 会被上层当成合法题号继续 +1，
     * 数据不一致会被静默放大成"题号整体错位"，不如当场抛出，让它早失败早暴露。
     *
     * <p>注意：这里用的 questions（QuestionMapper）是类里原有字段，本方法只是复用，
     * 不需要为此新建仓储类——同一个 AnswerRepository 里已经能同时操作答案表和题目表。
     */
    public int findSequenceNo(UUID questionId) {
        // 按主键等值查一行：questionId 是主键，走主键索引，不需要再带任何别的条件。
        QuestionEntity question = questions.selectOne(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getId,
                                questionId.toString()   // 领域层统一用 UUID，落库是字符串，这里转一次
                        )
        );
        if (question == null
                || question.getSequenceNo() == null) {
            throw new IllegalArgumentException(
                    "question sequence not found: " + questionId
            );
        }
        return question.getSequenceNo();   // 返回题号，调用方 +1 就是下一题序号
    }
    // 新增结束

    /**
     * 判断会话是否还有"在途评分"：存在 status=SUBMITTED 的答案即视为评分未落库。
     *
     * <p>谁在用：报告生成前等待所有评分写回。用户可能"答完最后一道题立即点生成报告"，
     * 而评分是异步写回的（答案先插成 SUBMITTED，评分线程算完再置 JUDGED）；若不等它写完，
     * 报告会把最后几道题记成"无评分/未作答"。
     *
     * @param sessionId 面试会话 ID
     * @return true 表示还有答案在等评分
     */
    public boolean hasPendingJudges(UUID sessionId) {
        return mapper.selectCount(
                Wrappers.<AnswerEntity>lambdaQuery()
                        .eq(
                                AnswerEntity::getSessionId,
                                sessionId.toString()
                        )
                        .eq(
                                AnswerEntity::getStatus,
                                "SUBMITTED"
                        )
        ) > 0;
    }

    /**
     * 加载某场面试的全部答案文本，拼接成一段完整转写文本。
     *
     * <p>为什么需要这个方法：表现分析需要整场面试的"说话内容"作为输入。
     * 语音答案在提交时已经以转写文本的形式存入 interview_answer.answer，
     * 因此直接按 sessionId 汇总即可，不依赖 WebSocket 连接仍然存活
     * （面试结束后连接早已关闭）。
     *
     * <p>实现要点：按答案记录自增主键升序取出该会话的全部答案，
     * 过滤掉 null 或空白内容后，用换行符拼接成一段完整文本。
     * 该文本随后交给表现分析（DemeanorAnalysisService）作为输入。
     *
     * @param sessionId 面试会话 ID
     * @return 所有非空答案用换行拼接的文本；一场面试可能为空字符串
     */
    public String loadSessionTranscript(UUID sessionId) {
        return mapper.selectList(
                        Wrappers.<AnswerEntity>lambdaQuery()
                                .eq(AnswerEntity::getSessionId,
                                        sessionId.toString())
                                .orderByAsc(AnswerEntity::getId)
                )
                .stream()
                .map(AnswerEntity::getAnswer)
                .filter(answer -> answer != null && !answer.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
