package com.example.interview.application;

import com.example.interview.ai.GeneratedQuestion;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class InterviewPreparationAsyncService {
    private static final Logger log =
            LoggerFactory.getLogger(
                    InterviewPreparationAsyncService.class
            );
            private final InterviewSessionMapper sessionMapper;
    private final ResumeMapper resumes;
    private final PositionMapper positions;
    private final SessionSnapshotRepository snapshots;
    private final InterviewSessionStateMachine machine;
    // **第一题生成后，立即异步生成第二题。**
    private final NextQuestionPreGeneratorService preGenerator;
    // **统一 AI 出题入口，避免首题继续使用固定字符串占位。**
    private final InterviewAiFacade ai;
    private final QuestionMapper questions;
    // 从这里开始是新增的：心跳服务。为什么注入到这里：prepare 是准备任务的唯一入口，
    // 只有它知道任务开始与结束的时刻，register / unregister 才能严格配对。
    private final HeartbeatService heartbeat;
    // 新增结束

    public InterviewPreparationAsyncService(
            InterviewSessionMapper sessionMapper,
            ResumeMapper resumes,
            PositionMapper positions,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            NextQuestionPreGeneratorService preGenerator,
            InterviewAiFacade ai,
            QuestionMapper questions,
            // 从这里开始是新增的：构造器末尾追加心跳服务依赖（Spring 按类型注入，
            // 追加在末尾不会打乱既有参数顺序）。
            HeartbeatService heartbeat
            // 新增结束
    ) {
        this.sessionMapper = sessionMapper;
        this.resumes = resumes;
        this.positions = positions;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preGenerator = preGenerator;
        this.ai = ai;
        this.questions = questions;
        // 从这里开始是新增的：
        this.heartbeat = heartbeat;
        // 新增结束
    }
    /**
     * 推进一步会话状态：加载快照 → 状态机流转 → CAS 写回。
     *
     * <p>为什么叫"推进"而不是"跳转"：状态机严格按注册的转移规则走，非法事件会直接抛错，
     * 这里只负责"取当前快照、请求流转、用 CAS 保证并发下只有赢得版本的快照落库"三件事。
     *
     * <p>返回 false 的含义：compareAndSet 失败 = 版本已被其它线程推进（本请求拿到的是旧快照），
     * 调用方应直接返回、不要继续基于旧快照做后续工作（比如出题），以免覆盖别人的进展。
     *
     * @param sessionId   会话 ID
     * @param event       本次要触发的事件
     * @param requestName 事件幂等键前缀，调用方传业务名，方法内拼上随机数保证每次请求唯一
     * @return 快照落库成功返回 true；CAS 失败（版本被并发抢占）返回 false
     */
    private boolean advance(UUID sessionId, InterviewEvent event, String requestName) {
        InterviewSessionSnapshot oldValue = snapshots.load(sessionId);
        var result = machine.advance(oldValue, event, requestName + "-" + UUID.randomUUID());
        boolean updated = snapshots.compareAndSet(oldValue, result.snapshot());
        if(!updated)
        {
            log.warn(
                    "snapshot compareAndSet failed, sessionId={}, event={}, version={}",
                    sessionId,
                    event,
                    oldValue.version()
            );
            return false;
        }
        log.info(
                "snapshot advanced, sessionId={}, event={}, state={}",
                sessionId,
                event,
                result.snapshot().state()
        );
        return true;

    }

    @Async ("interviewPreparationTaskExecutor")
    public void prepare(UUID sessionId)
    {
        // 从这里开始是新增的：任务开始，先注册心跳。必须写在 try 外面——
        // 写在 try 里面的话，前面任何一行抛异常都等于"从来没注册过"，
        // 而这个任务此刻其实正在跑，补偿器会把"处理中"误判成"线程已死"。
        heartbeat.register(sessionId);
        // 新增结束
        try
        {
            InterviewSessionEntity 
            session = sessionMapper.selectById(sessionId.toString());

            if ((session == null)) {
                throw new IllegalArgumentException
                (
                    "interview session not found"
                );
            }
            ResumeEntity resume = resumes.selectById(session.getResumeId());
            PositionEntity position = positions.selectById(session.getPositionId());
            if (resume == null || position == null) {
                throw new IllegalArgumentException(
                        "resume or position not found"
                );
            }
            if (!advance(
                    sessionId,
                    InterviewEvent.POSITION_MATCHED,
                    "position-matched"
            )) {
                // 如果 CAS 更新失败（说明其他线程已经推进了版本），直接返回，不继续后续出题。
                return;
            }
            GeneratedQuestion firstQuestion =
                    ai.generateQuestion(
                            sessionId,                      // 会话 ID，用于关联题目
                            position.getName(),              // 岗位名称，供 AI 了解职位方向
                            position.getDescription(),       // 岗位描述，供 AI 了解职责细节
                            position.getSkills(),            // 技能要求，供 AI 针对性地出题
                            resume.getParsedContent(),       // 简历解析内容，供 AI 结合候选人背景出题
                            1                                // 题目序号，标记为第一题
                    );
                    // 将 AI 生成的首题持久化到数据库。
            QuestionEntity firstEntity = new QuestionEntity();
            firstEntity.setId(UUID.randomUUID().toString());   // 生成全局唯一 ID
            firstEntity.setSessionId(sessionId.toString());    // 关联面试会话
            firstEntity.setPositionId(session.getPositionId());// 关联岗位
            firstEntity.setContent(firstQuestion.content());   // 题目内容
            firstEntity.setCategory(firstQuestion.category()); // 题目分类（如技术、项目经验等）
            firstEntity.setSequenceNo(1);                      // 第一题，序号为 1
            firstEntity.setStatus("ACTIVE");                   // 状态为激活，表示该题正在使用
            // 从这里开始是修改的：首题插入改为"先查后插 + 唯一键兜底"。
            // 为什么必须改：取题接口的同步兜底（createQuestionJson）也可能插入 sequence=1 的
            // 首题，且它插入的也是 ACTIVE。若 prepare 无条件 insert，两条路径并发时后插的那条
            // 会撞上 uk_question_session_sequence 唯一键，或者（旧库缺唯一键时）双写出两条首题，
            // 前端会看到两道"第 1 题"、报告统计也随之错乱。先查后插让 prepare 复用取题已建好的
            // 首题，唯一键兜底挡住"两侧恰好同一毫秒插入"的并发竞态。
            List<QuestionEntity> existingFirst = questions.selectList(
                    Wrappers.<QuestionEntity>lambdaQuery()
                            .eq(QuestionEntity::getSessionId, sessionId.toString())
                            .eq(QuestionEntity::getSequenceNo, 1)
                            .in(QuestionEntity::getStatus, "ACTIVE", "PRE_GENERATED")
                            .last("LIMIT 1")
            );
            if (existingFirst.isEmpty()) {
                try {
                    questions.insert(firstEntity);
                } catch (DataIntegrityViolationException conflict) {
                    // 并发的取题兜底已经插入了首题：这是正常信号，本次跳过，后续推进照常。
                    log.info(
                            "first question inserted by concurrent request, skip, sessionId={}",
                            sessionId
                    );
                }
            } else {
                // 首题已被取题同步兜底建好：复用，不再新插一道。
                log.info(
                        "first question already exists, reuse, sessionId={}",
                        sessionId
                );
            }
            // 修改结束
            // 记录日志，方便追踪题目生成情况（因为异步异常无法返回给前端）。
            log.info(
                    "first question generated, sessionId={}, question={}",
                    sessionId,
                    firstQuestion.content()
            );
            advance(
                    sessionId,
                    InterviewEvent.QUESTION_READY,
                    "question-ready"
            );

            // 第六步：首题已激活，立即启动第二题的预生成，提前准备下一道题目以提升用户体验。
            // 序号为 2，表示生成第二题。
            preGenerator.preGenerate(sessionId, 2);

        }
        catch (Exception e) {
            // 异步线程中的异常不会直接返回给已结束的 HTTP 请求，因此必须记录完整上下文以便排查问题。
            log.error(
                    "interview preparation failed, sessionId={}",
                    sessionId,
                    e
            );
        }
        // 从这里开始是新增的：任务结束（含异常退出），注销心跳。
        // 为什么用 finally 而不是接在 catch 后面：catch 只盖住"抛异常"这一条路径，
        // finally 盖住所有路径；任务线程一旦退出就删掉心跳，不留"看起来还活着"的假信号。
        finally {
            heartbeat.unregister(sessionId);
        }
        // 新增结束
    }
}
