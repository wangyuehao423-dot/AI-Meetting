package com.example.interview.application;

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
import com.example.interview.infrastructure.singleflight.RedisSingleflight;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewState;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
// 从这里开始是新增的：识别"单飞等待超时"的可重试信号。
// 为什么需要它：异步 prepare 尚在通过门面单飞生成首题时，取题接口在相同的
// question-view key 下会等待生成结果；若等待窗口耗尽（门面生成 > 30s），底层抛出
// RetryableSingleflightException。它是"生成方还活着、稍后必有结果"的明确信号，
// 取题接口捕获后在本地短暂重试，而不是直接把 500 抛给前端。
import com.example.interview.infrastructure.singleflight.RetryableSingleflightException;
// 新增结束
// 从这里开始是新增的：捕获唯一键冲突（可能被 Spring/MyBatis 包装成 DataIntegrityViolationException）。
// 为什么需要它：取题接口的同步兜底与 /start 异步的 prepare 都可能插入同一道首题，
// 并发时只有一个插进去、另一个撞上唯一键 uk_question_session_sequence，需要捕获后重查返回，而不是 500 给前端。
import org.springframework.dao.DataIntegrityViolationException;
// 新增结束
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
// 从这里开始是新增的：Objects.nonNull 用于过滤掉 max() 在无数据时返回的 NULL。
import java.util.Objects;
// 新增结束
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/interviews")
public class QuestionController {
    private final PositionController positionController;
    // 面试会话 Mapper，用于查询会话是否存在以及获取会话详情。
    private final InterviewSessionMapper sessions;
    // 岗位 Mapper，用于查询岗位名称、描述和技能要求。
    private final PositionMapper positions;
    // 简历 Mapper，用于查询候选人的简历内容。
    private final ResumeMapper resumes;
    // 题目 Mapper，用于查询已生成的题目和保存新生成的题目。
    private final QuestionMapper questions;
    // AI Facade，封装了调用 AI 生成题目的业务逻辑。
    private final InterviewAiFacade ai;
    // 请求合并器，保证相同 key 的并发请求只执行一次 AI 调用。
    private final RedisSingleflight singleflight;
    // 从这里开始是新增的：会话快照仓储，用于取题前等待上一题评分/追问闭环。
    private final SessionSnapshotRepository snapshots;
    // 新增结束
    // Jackson JSON 处理器，用于将对象序列化为 JSON 或反序列化 JSON 为对象。
    private final ObjectMapper mapper;

    public QuestionController(
            // 面试会话 Mapper，由 Spring 自动注入。
            InterviewSessionMapper sessions,
            // 岗位 Mapper，由 Spring 自动注入。
            PositionMapper positions,
            // 简历 Mapper，由 Spring 自动注入。
            ResumeMapper resumes,
            // 题目 Mapper，由 Spring 自动注入。
            QuestionMapper questions,
            // AI Facade 实例，由 Spring 自动注入。
            InterviewAiFacade ai,
            // 请求合并器实例，由 Spring 自动注入。
            RedisSingleflight singleflight,
            // 从这里开始是新增的：快照仓储实例，由 Spring 自动注入。
            SessionSnapshotRepository snapshots,
            // 新增结束
            // JSON 处理器实例，由 Spring 自动注入。
            ObjectMapper mapper, PositionController positionController
    ) {
        this.sessions = sessions;
        this.positions = positions;
        this.resumes = resumes;
        this.questions = questions;
        this.ai = ai;
        this.singleflight = singleflight;
        this.snapshots = snapshots;
        this.mapper = mapper;
        this.positionController = positionController;
    }
    @PostMapping("/{sessionId}/questions")
    public QuestionView nextQuestion(@PathVariable("sessionId") UUID sessionId) throws JsonProcessingException
    {
        InterviewSessionEntity session = sessions.selectById(sessionId.toString());
        if(session == null)
        {
            throw new IllegalArgumentException(
                "interview session not found"
            );
        }
        PositionEntity position = positions.selectById(session.getPositionId());
        ResumeEntity resume = resumes.selectById(session.getResumeId());
        if(position == null || resume == null)
        {
            throw new IllegalArgumentException(
                "position or resume not found"
            );
        }
        // 从这里开始是修改的：等待上一题评分/追问闭环 + 等待准备完成。
        // 为什么必须等 MATCHING：prepare 是 @Async 异步跑（线程池可能被评分任务占满而排队），
        // 而取题接口的同步兜底会自己调 AI 生成题目并落库。若 prepare 的 POSITION_MATCHED
        // （MATCHING→QUESTIONING）还没推进，快照就停在 MATCHING，此时把题返回给前端，
        // 用户作答必然撞上状态机非法转移 MATCHING+ANSWER_SUBMITTED，接口 500。
        // 所以取题前先等快照离开 MATCHING（prepare 至少完成岗位匹配推进），
        // 之后同步兜底生成的题在 QUESTIONING/ANSWERING 状态下作答都是合法转移。
        // JUDGING/ASKING 同理：评分（JUDGE_DONE→ASKING）与追问（ASK_COMPLETED→QUESTIONING）
        // 未闭环时提交答案也是非法转移。三者统一在这里轮询等待；超时（60s）抛可重试异常，
        // 让前端稍后重试，而不是带着坏状态返回一道不能答的题。
        InterviewSessionSnapshot snapshot = snapshots.load(sessionId);
        // 从这里开始是新增的：会话已结束时拒绝取题。
        // 为什么必须加：面试结束后（ENDED 是终态）取题接口没有任何守卫，会直接
        // 继续生成第 4+ 道新题并返回 200——报告生成后还能接着"面试"，违反
        // "结束后面试彻底完结"的业务语义。抛 IllegalStateException 映射为 409，
        // 前端在 ENDED 状态下不再取题，接口层兜底拒绝。
        if (snapshot.state() == InterviewState.ENDED) {
            throw new IllegalStateException(
                    "面试已结束，不能继续取题"
            );
        }
        // 新增结束
        long deadline = System.currentTimeMillis() + 60_000L;
        while ((snapshot.state() == InterviewState.JUDGING
                        || snapshot.state() == InterviewState.ASKING
                        || snapshot.state() == InterviewState.MATCHING)
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            snapshot = snapshots.load(sessionId);
        }
        if (snapshot.state() == InterviewState.JUDGING
                || snapshot.state() == InterviewState.ASKING
                || snapshot.state() == InterviewState.MATCHING) {
            throw new IllegalStateException(
                    "interview is not ready, please retry later"
            );
        }
        // 修改结束

        // 第三步：先取"尚未作答的最小序号题"。
        // 为什么必须这一步：prepare 会把首题直接激活成 ACTIVE、同步兜底插入的题也是 ACTIVE，
        // 若取题只按 max(sequence_no)+1 计算下一题，会跳过这些"已就绪但还没展示/作答"的题，
        // 造成跳号（用户看到的第 1 题其实是第 2 题）；而 ACTIVE 的题作答后仍是 ACTIVE，
        // 所以还要排除已作答的题，才能锁定真正待展示的那一道。
        QuestionView pending = takeNextPendingQuestion(sessionId);
        if (pending != null) {
            return pending;   // 命中即返回，下面的同步生成不会执行
        }

        // 第四步：全部已作答，查询当前会话中已有的最大题目序号。
        Integer maxSequence = questions.selectObjs(
                Wrappers.<QuestionEntity>query()
                        .select("max(sequence_no)")
                        .eq(
                                "session_id",
                                sessionId.toString()
                        )
        ).stream()
                // 从这里开始是新增的：过滤掉 max() 返回的 NULL，再取第一个。
                // 为什么必须有这一行：会话里一题都没有时，SQL 的 max(sequence_no) 并不是
                // "查不到行"，而是"一行、值为 NULL"，所以 selectObjs 返回的列表是 [null]。
                // Stream.findFirst() 内部用 Optional.of(value) 包装结果，遇到 null 会直接抛
                // NullPointerException（堆栈落在 Optional.of 与本方法这一行），接口变成 500。
                // 触发场景正是"第一题还没生成出来时前端来取题"：/start 只是异步触发准备，
                // 后台还没写完 interview_question 那几毫秒里，max() 必然返回 NULL。
                // 过滤后列表为空，走 orElse(0)，sequence 从 1 开始，进入下面的预生成/同步兜底。
                .filter(Objects::nonNull)
                // 新增结束
                .findFirst()
                .map(value -> ((Number) value).intValue())
                .orElse(0);
        int sequence = maxSequence + 1;

        // 第四步：构造 RedisSingleflight 的 key，用于请求合并。
        // 从这里开始是修改的：key 命名空间从 "question:" 隔离成 "question-view:"。
        // 为什么必须换前缀：InterviewAiFacade.generateQuestion 也用它自己的 singleflight
        // 缓存 "question:{sessionId}:{seq}"——它缓存的是"原始 AI 出题 JSON（只有
        // content/category）"。若取题接口沿用同一个 key：① 回放出错类型的缓存，得到
        // id/sequenceNo/status 全是 null 的题，前端 status==='ACTIVE' 校验必失败；
        // ② 取题成为该 key 的持有者后，其 producer（createQuestionJson）内部又会调
        // 门面.singleflight(同 key)，等于在自己已持有的锁上等待，30s 超时抛 500。
        // 换用独立的 "question-view:" 前缀后，取题缓存只存完整的 QuestionView JSON，
        // 也不会与门面的锁互相占用，两个症状一起消除。
        String key = "question-view:"
                + sessionId
                + ":"
                + sequence;
        // 修改结束（第五步：通过 RedisSingleflight 执行请求合并）
        // 相同 key 的并发请求只有一个会真正执行 createQuestionJson，其余复用结果。
        // 从这里开始是修改的：新增捕获"拒绝过期出题结果"并重查题库。
        // 为什么必须加：取题接口的同步兜底与 /start 异步的 prepare 会并发生成同一道题，
        // 门面 executeFenced 每次调用都自增 fence token——先触发的拿到 T1、后触发的拿到 T2，
        // 两个生成都完成时，先者 acceptFence(T1) 必然失败（T2 已刷新），抛"拒绝过期出题结果"。
        // 但失败的往往恰恰是取题这一侧，而库里那道题是 prepare 落库成功的、就躺在那。
        // 此时正确动作是短暂等待后回到循环重查（createQuestionJson 的幂等先查会直接命中），
        // 而不是把 409 抛给前端——否则 start 后立刻取题就随机失败，表现成间歇性 409。
        int attempts = 0;
        while (true) {
            try {
                String json = singleflight.execute(
                        key,
                        Duration.ofMinutes(5),
                        () -> createQuestionJson(
                                sessionId,
                                session,
                                position,
                                resume,
                                sequence
                        ),
                        value -> value
                );
                // 第六步：将 JSON 字符串反序列化为 QuestionView 返回给前端。
                return mapper.readValue(json, QuestionView.class);
            } catch (RetryableSingleflightException retryable) {
                if (++attempts >= 4) {
                    throw retryable;
                }
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw retryable;
                }
            } catch (IllegalStateException stale) {
                // 只重试"另一生成方抢先落库"这一种过期结果；其它 IllegalStateException 原样抛。
                if (stale.getMessage() == null
                        || !stale.getMessage().contains("拒绝过期出题结果")) {
                    throw stale;
                }
                if (++attempts >= 4) {
                    throw stale;
                }
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw stale;
                }
            }
        }
        // 修改结束
    }

    // 生成当前这一道新题目：先调用 AI 生成题目内容与考察分类，再把题目持久化到数据库，
    // 最后序列化成 QuestionView 的 JSON 字符串返回。该方法作为 singleflight 的 producer 传入，
    // 保证相同 sessionId 与 sequence 的并发请求只真正执行一次 AI 调用。
    // 参数 sessionId：当前面试会话 ID，用于定位会话并写入题目记录。
    // 参数 session：当前面试会话实体，用于读取职位、简历等信息。
    // 参数 position：目标岗位实体，提供岗位名称、描述和技能要求作为 AI 的输入。
    // 参数 resume：候选人简历实体，提供解析出的简历正文作为 AI 的输入。
    // 参数 sequence：当前题目的序号，从 1 开始递增，决定展示顺序。
    // 返回值：可直接反序列化为 QuestionView 的 JSON 字符串。
    private String createQuestionJson(
            UUID sessionId,
            InterviewSessionEntity session,
            PositionEntity position,
            ResumeEntity resume,
            int sequence
    ) {
        // 从这里开始是新增的：幂等先查再插。
        // 为什么必须加这一层：首题有两个生成入口——/start 异步触发的 prepare 会插一道
        // sequence=1 的 ACTIVE 题，取题接口的同步兜底也可能插 sequence=1。两者都在"该题号
        // 还没有题"时各插一次，后插的那个撞上唯一键 uk_question_session_sequence
        // （堆栈表现是 Duplicate entry 'sessionId-序'，接口直接 500）。
        // 所以在插入前先按 (sessionId, sequence) 查一次，prepare 已经生成好就直接复用它，
        // 不再新插一道。
        List<QuestionEntity> existing = questions.selectList(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getSessionId,
                                sessionId.toString()
                        )
                        .eq(
                                QuestionEntity::getSequenceNo,
                                sequence
                        )
                        .in(
                                QuestionEntity::getStatus,
                                "ACTIVE",
                                "PRE_GENERATED"
                        )
                        .last("LIMIT 1")
        );
        if (!existing.isEmpty()) {
            return toViewJson(existing.get(0));
        }
        // 新增结束

        // 调用 AI 生成题目的正文和考察分类。
        GeneratedQuestion generated = ai.generateQuestion(
                sessionId,
                position.getName(),
                position.getDescription(),
                position.getSkills(),
                resume.getParsedContent(),
                sequence
        );
        // 构造题目实体并保存到数据库，初始化状态为 ACTIVE 表示题目可用。
        QuestionEntity entity = new QuestionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId.toString());
        entity.setPositionId(position.getId());
        entity.setContent(generated.content());
        entity.setCategory(generated.category());
        entity.setSequenceNo(sequence);
        entity.setStatus("ACTIVE");
        // 从这里开始是新增的：插入改为捕获唯一键冲突。
        // 为什么还要这一层：先查后插挡得住"prepare 已生成"的顺序冲突，但挡不住"两侧恰好
        // 同一毫秒插入"的并发竞态。一旦撞上唯一键，就重新读一次已存在的那一行返回，
        // 而不是把异常抛给前端变成 500。
        try {
            questions.insert(entity);
        } catch (DataIntegrityViolationException conflict) {
            List<QuestionEntity> winner = questions.selectList(
                    Wrappers.<QuestionEntity>lambdaQuery()
                            .eq(
                                    QuestionEntity::getSessionId,
                                    sessionId.toString()
                            )
                            .eq(
                                    QuestionEntity::getSequenceNo,
                                    sequence
                            )
                            .in(
                                    QuestionEntity::getStatus,
                                    "ACTIVE",
                                    "PRE_GENERATED"
                            )
                            .last("LIMIT 1")
            );
            if (winner.isEmpty()) {
                throw conflict;
            }
            return toViewJson(winner.get(0));
        }
        // 新增结束
        return toViewJson(entity);
    }

    // 从这里开始是新增的：把已落库（或刚插入）的题目实体转成返回给前端的视图 JSON。
    // 为什么单独成方法：幂等先查与冲突兜底两条路径都要"实体 -> JSON"，共用一份转换逻辑
    // 避免重复；同时把 createQuestionJson 的注意力留在"生成 + 落库"上。
    private String toViewJson(QuestionEntity entity) {
        QuestionView view = new QuestionView(
                UUID.fromString(entity.getId()),
                entity.getContent(),
                entity.getCategory(),
                entity.getSequenceNo(),
                entity.getStatus()
        );
        // 序列化失败说明视图对象不可用，包装为运行时异常向外抛出，避免被吞。
        try {
            return mapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialize question view",
                    e
            );
        }
    }
    // 新增结束

    // 从这里开始是新增的（改动二）：takeNextPendingQuestion，插在 createQuestionJson 之后、类结束之前。
    /**
     * 取出并激活"尚未作答的最小序号题"。
     *
     * <p>待展示的题有两类：PRE_GENERATED（预生成待激活，CAS 更新成 ACTIVE 后返回）
     * 与 ACTIVE（prepare 或同步兜底已激活、但用户还没作答）。两者都通过
     * "无对应答案记录"来确认还没被消费——ACTIVE 的题作答后状态仍是 ACTIVE，
     * 不能只看状态判断是否展示过。
     *
     * <p>为什么按最小序号而不是 max+1：出题是严格递增的，未作答的题必然是最小序号的那道；
     * 按 max+1 会跳过 prepare 已激活但尚未展示的首题（跳号）。取完最小序号、用户作答后，
     * 下一轮取题会自然前进到下一道未作答题。
     *
     * <p>CAS 失败（PRE_GENERATED 被并发线程抢先激活）返回 null，让调用方走同步兜底，
     * 功能正确性不依赖预生成一定成功。
     *
     * @param sessionId 面试会话 ID
     * @return 下一道待展示题；没有未作答的题时返回 null
     */
    private QuestionView takeNextPendingQuestion(
            UUID sessionId
    ) {
        // 第一步：查"无答案记录"的最小序号题。
        // notExists 排除已作答的题（interview_answer 的初次与追问作答都算已作答）。
        List<QuestionEntity> pending = questions.selectList(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getSessionId,
                                sessionId.toString()     // 只在本会话里找
                        )
                        .in(
                                QuestionEntity::getStatus,
                                "ACTIVE",                // 已激活但尚未作答
                                "PRE_GENERATED"          // 预生成待激活
                        )
                        .notExists(
                                "SELECT 1 FROM interview_answer a WHERE a.question_id = interview_question.id"
                        )
                        .orderByAsc(QuestionEntity::getSequenceNo)   // 最小序号在前
        );
        if (pending.isEmpty()) {
            // 没有未作答的题：全部已消费，交给调用方生成下一题。
            return null;
        }
        QuestionEntity entity = pending.get(0);

        // 第二步：若是预生成题，CAS 更新成 ACTIVE（set 是"要改成什么"，
        // eq 里的 status 是"当前必须还是什么"）。
        if ("PRE_GENERATED".equals(entity.getStatus())) {
            int updated = questions.update(
                    null,   // 第一个参数传 null：本次只靠 lambdaUpdate 里的 set 改字段
                    Wrappers.<QuestionEntity>lambdaUpdate()
                            .set(
                                    QuestionEntity::getStatus,
                                    "ACTIVE"           // 从"预生成"变成"正在展示"
                            )
                            .eq(
                                    QuestionEntity::getId,
                                    entity.getId()     // 主键定位到这一行
                            )
                            .eq(
                                    QuestionEntity::getStatus,
                                    "PRE_GENERATED"    // CAS 条件：抢到激活权的那个线程才改得动
                            )
            );
            if (updated != 1) {
                // 影响行数不是 1：这条题已经被别人激活，本次空手而归，交给同步兜底。
                return null;
            }
            // 更新成功。把内存里的状态同步过来，返回给前端的才是一道"当前题"。
            entity.setStatus("ACTIVE");
        }

        // 第三步：实体转前端视图：只挑前端要的字段，不把整行实体丢出去。
        return new QuestionView(
                UUID.fromString(entity.getId()),   // 主键字符串转回 UUID
                entity.getContent(),               // 题目正文
                entity.getCategory(),              // 题目分类
                entity.getSequenceNo(),            // 题号
                entity.getStatus()                 // 状态，走到这里已经是 ACTIVE
        );
    }
    // 新增结束
}