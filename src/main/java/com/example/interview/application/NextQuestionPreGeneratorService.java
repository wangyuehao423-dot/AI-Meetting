package com.example.interview.application;

// 从这里开始是新增的：补全这个类需要的 import。
// 为什么是这几段上下文：AI 出题要"岗位 + 简历"两段上下文，题目必须挂在某个会话上，
// 生成结果最后要落进 interview_question；缺任何一段，模型只能出一道和候选人无关的通用题。
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.ai.GeneratedQuestion;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 下一题预生成服务。
 *
 * <p>它在链路里的位置：AnswerCommandService 提交答案之后、FollowUpGenerationService
 * 追问落库之后，都会调一次 preGenerate；它在后台把"下一题"提前生成好写进库，
 * QuestionController 取题时优先拿它，用户切题就不用等 AI。
 *
 * <p>三条必须守住的性质：
 * 幂等——同一会话同一序号只允许一行，重复触发靠"先查后插 + 唯一键兜底"挡掉；
 * 容错——预生成是锦上添花，失败绝不能让提交答案、追问生成这些主流程跟着失败；
 * 不阻塞——方法上标 @Async 扔进独立线程池，调用方调完立即返回。
 */
// 原有：@Service 与类名保持不变（调用方一直按 NextQuestionPreGeneratorService 注入）。
@Service
public class NextQuestionPreGeneratorService {

    // 从这里开始是新增的：字段与构造器。
    // 日志对象：预生成失败只记日志不往外抛，出错痕迹全靠它留下来。
    private static final Logger log =
            LoggerFactory.getLogger(NextQuestionPreGeneratorService.class);

    private final InterviewSessionMapper sessions;   // 按会话 ID 取会话，顺带拿到职位 ID 与简历 ID
    private final PositionMapper positions;          // 岗位三要素（名称/描述/技能），决定出题方向
    private final ResumeMapper resumes;              // 简历解析正文，让题目贴着候选人经历出
    private final QuestionMapper questions;          // 开头的幂等查询与结尾的插入都走它
    private final InterviewAiFacade ai;              // 统一 AI 出题入口；本类只负责把上下文拼好

    // 构造器注入五个协作者，全部落到 final 字段：服务不可变，天然可并发。
    public NextQuestionPreGeneratorService(
            InterviewSessionMapper sessions,
            PositionMapper positions,
            ResumeMapper resumes,
            QuestionMapper questions,
            InterviewAiFacade ai
    ) {
        this.sessions = sessions;
        this.positions = positions;
        this.resumes = resumes;
        this.questions = questions;
        this.ai = ai;
    }
    // 新增结束

    /**
     * 预生成第 nextSequenceNo 题。
     *
     * @param sessionId      面试会话 ID
     * @param nextSequenceNo 下一题的序号（当前题序号 + 1，由调用方算好）
     *
     * <p>为什么不返回 Future：调用方不需要结果。预生成是"尽力而为"的优化，
     * 失败了 QuestionController 的同步兜底仍能保证功能正确，所以直接 void。
     *
     * <p>线程池用 questionGenerationTaskExecutor（见 AsyncConfig），
     * 与评分、追问、报告各自独立，AI 变慢也不会把别的任务堵在后面。
     */
    // 原有：public void preGenerate(UUID sessionId, int nextSequenceNo) 这一行与空壳一字不差；
    // @Async 是新增的——@Async 靠 Spring 代理生效，不走容器（自己 new）调用就是普通同步方法。
    @Async("questionGenerationTaskExecutor")
    public void preGenerate(
            UUID sessionId,
            int nextSequenceNo
    ) {
        // 整个方法体被 try 包住：@Async 方法抛出的异常回不到调用方（调用方早已返回），
        // 只能在这里自己接住，否则会被 AsyncUncaughtExceptionHandler 悄悄吃掉，连日志都没有。
        try {
            // ---- 第一步：幂等检查 ----
            // 为什么查 PRE_GENERATED 和 ACTIVE 两个状态：
            //   PRE_GENERATED —— 别的线程已经预生成过这道题；
            //   ACTIVE        —— 用户已经切到这道题，或者同步兜底已经建过这道题。
            // 两种情况都不能再生成，否则会撞上 uk_question_session_sequence 唯一键。
            List<QuestionEntity> existing = questions.selectList(
                    Wrappers.<QuestionEntity>lambdaQuery()   // 用方法引用写字段名，字段写错编译期就报错
                            .eq(
                                    QuestionEntity::getSessionId,
                                    sessionId.toString()      // 只在本会话内找，UUID 落库转字符串
                            )
                            .eq(
                                    QuestionEntity::getSequenceNo,
                                    nextSequenceNo            // 只找目标题号那一行
                            )
                            .in(
                                    QuestionEntity::getStatus,
                                    "PRE_GENERATED",
                                    "ACTIVE"
                            )
                            .last("LIMIT 1")
            );
            if (!existing.isEmpty()) {
                // 已经有人生成过同一道题：直接收工。这就是幂等——重复触发不会多出一行题。
                return;
            }

            // ---- 第二步：组装 AI 出题上下文 ----
            // 为什么不让调用方查好再传进来：调用方只关心"该生成下一题了"，
            // 需要哪些数据是本服务的实现细节，自己查职责更干净。
            // 主键查询会话：题目必须挂在某个会话下，顺带拿到职位 ID 与简历 ID。
            InterviewSessionEntity session =
                    sessions.selectById(sessionId.toString());
            if (session == null) {
                // 会话不存在说明调用方给了脏 ID，让这次后台任务失败（外层 catch 会记日志）。
                throw new IllegalArgumentException(
                        "interview session not found"
                );
            }
            // 岗位：给 AI 岗位名称、描述、技能要求，出的题才贴岗位方向。
            PositionEntity position =
                    positions.selectById(session.getPositionId());
            // 简历：AI 结合候选人经历出题，比通用题库更有针对性。
            ResumeEntity resume =
                    resumes.selectById(session.getResumeId());
            if (position == null || resume == null) {
                // 上下文缺一块就不出题：宁可这次预生成失败，也别凭空造一道没有依据的题。
                throw new IllegalArgumentException(
                        "position or resume not found"
                );
            }

            // ---- 第三步：调 AI 出题 ----
            // 这里不设超时：后台线程被占住不影响任何用户请求，真挂了也只是这次预生成失败，
            // 用户切题时还有 QuestionController 的同步兜底。
            GeneratedQuestion generated = ai.generateQuestion(
                    sessionId,                      // 会话 ID，用于让 AI 结合整场进度出题
                    position.getName(),             // 岗位名称，如"Java 高级工程师"
                    position.getDescription(),      // 岗位描述，模型据此判断考察范围
                    position.getSkills(),           // 技能要求，模型据此挑考点
                    resume.getParsedContent(),      // 简历解析正文，模型据此让题落到候选人经历上
                    nextSequenceNo                  // 题号，进提示词参与难度梯度控制
            );

            // ---- 第四步：落库，状态标 PRE_GENERATED ----
            // 不写库这次预生成等于没做；标成 ACTIVE 则等于"用户已看过"，切题时会被判成已消费。
            QuestionEntity entity = new QuestionEntity();
            entity.setId(UUID.randomUUID().toString());        // 主键由应用层生成，与表设计一致
            entity.setSessionId(sessionId.toString());         // UUID 落库统一转 36 位字符串
            entity.setPositionId(session.getPositionId());     // 冗余存职位 ID，报告按职位统计要用
            entity.setContent(generated.content());            // AI 正文原样落库，不做二次加工
            entity.setCategory(generated.category());          // 分类原样落库，前端按分类展示
            entity.setSequenceNo(nextSequenceNo);              // 题号用调用方算好的"下一题序号"
            entity.setStatus("PRE_GENERATED");                 // 关键：标预生成，用户切题时才会被优先取走
            try {
                questions.insert(entity);                      // 写库这一刻起，下一题已经躺在库里等着了
            } catch (DataIntegrityViolationException duplicate) {
                // 两个预生成线程同时通过幂等检查、同时落库时，后插的会撞上唯一键。
                // 这是"别人已经预生成好同一道题"的正常信号，静默收工，不记 error。
            }

        } catch (Exception e) {
            // 只记日志不重抛：异步方法抛出去没人接，而且预生成失败不该让任何主流程请求失败。
            // 把 sessionId 和题号一起记下来，排查时能直接定位是哪一场哪一题没生成出来。
            log.error(
                    "pre-generate question failed, sessionId={}, sequenceNo={}",
                    sessionId,
                    nextSequenceNo,
                    e
            );
        }
    }
}
