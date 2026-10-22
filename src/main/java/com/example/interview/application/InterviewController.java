package com.example.interview.application;

import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;

import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;


@RestController
@RequestMapping("/api/interviews")
public class InterviewController
{
    // InterviewController.java 中创建会话所需的属性。
    private final ResumeMapper resumes;
    private final InterviewSessionMapper sessionMapper;
    private final SessionSnapshotRepository snapshots;
    private final InterviewSessionStateMachine machine;
    private final InterviewPreparationAsyncService preparationTasks;
    // 从这里开始是新增的：追问仓储。用来回答"刚才这道题有没有追问要答"。
    // 追问是异步生成的（评分 → 追问裁决 → LiteFlow 链落库），前端提交完原题答案、
    // 等到状态回到 QUESTIONING 时追问已经落库，这里就是那条链路在前端的唯一出口。
    private final FollowUpRepository followUps;
    // 从这里开始是新增的：会话恢复服务。用来在重连时把中断的异步步骤幂等补跑，
    // 兑现亮点2里"主动恢复 + 幂等补偿"那一环（详见 SessionRecoveryService 类头注释）。
    private final SessionRecoveryService recovery;
    // 新增结束


    // Spring 通过构造器注入会话创建流程所需的所有依赖。
    public InterviewController(
            ResumeMapper resumes,
            InterviewSessionMapper sessionMapper,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            InterviewPreparationAsyncService preparationTasks,
            FollowUpRepository followUps,
            // 从这里开始是新增的：构造器末尾追加恢复服务（Spring 按类型注入）。
            SessionRecoveryService recovery
            // 新增结束
    ) {
        this.resumes = resumes;
        this.sessionMapper = sessionMapper;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preparationTasks = preparationTasks;
        this.followUps = followUps;
        this.recovery = recovery;
    }
    @PostMapping
    public SessionView create(@RequestParam("userId") Long userId,
                              @RequestParam("positionId") Long positionId,
                              @RequestParam("resumeId") Long resumeId)
    {
        var resume = resumes.selectById(resumeId);
        if (resume == null
                || !Objects.equals(
                userId,
                resume.getUserId()
        )
                || !"PARSED".equalsIgnoreCase(
                resume.getStatus()
        )) {
            throw new IllegalArgumentException(
                    "请先上传并完成简历解析"
            );
        }
        var id = UUID.randomUUID();
        var snapshot = snapshots.load(id);
        var entity = new InterviewSessionEntity();
        entity.setId(id.toString());
        entity.setUserId(userId);
        entity.setPositionId(positionId);
        entity.setResumeId(resumeId);
        entity.setState(
                snapshot.state().name()
        );
        sessionMapper.insert(entity);

        return new SessionView(
                id,
                snapshot.state(),
                snapshot.version()
        );
    }
    private void save(
            com.example.interview.domain.InterviewSessionSnapshot oldValue,
            com.example.interview.domain.InterviewSessionSnapshot newValue
    ) {
        if (!snapshots.compareAndSet(oldValue, newValue)) {
            throw new IllegalStateException(
                    "session version conflict"
            );
        }
    }
    private SessionView view(
            UUID sessionId,
            com.example.interview.domain.InterviewSessionSnapshot snapshot
    ) {
        return new SessionView(
                sessionId,
                snapshot.state(),
                snapshot.version()
        );
    }
    @PostMapping("/{sessionId}/start")
    public SessionView start(@PathVariable("sessionId") UUID sessionId)
    {
        var oldValue = snapshots.load(sessionId);
        var result = machine.advance(oldValue, InterviewEvent.START, UUID.randomUUID().toString());
        save(oldValue, result.snapshot());
        preparationTasks.prepare(sessionId);
        return view(sessionId,result.snapshot());
    }

    // 从这里开始是新增的：状态查询接口。项目里 InterviewController 目前没有这个
    // 映射，前端轮询会直接 404。只读接口，不改任何状态，因此不需要加锁或 CAS。
    /**
     * 返回面试会话的当前快照状态，不改写为不存在的状态名。
     *
     * <p>为什么直接返回状态机里的原始状态（如 ENDED）：前端需要区分
     * "面试结束"和"报告生成完成"两件事，任何一侧的改名都会让这个判断失真。</p>
     *
     * <p>为什么复用 view(UUID, snapshot)：本接口返回的字段与 create / start / end
     * 完全一致（sessionId / state / version），复用同一个转换方法可以保证
     * 前端拿到的会话视图在任何入口下形状都一样。</p>
     *
     * @param sessionId 面试会话 ID
     * @return 含 state 的会话视图；Redis 未命中时会懒加载 MongoDB，不会改任何状态
     */
    @GetMapping("/{sessionId}/state")
    public SessionView state(@PathVariable("sessionId") UUID sessionId)
    {
        // 直接读快照当前状态：Redis 未命中时会懒加载 MongoDB，不会改任何状态。
        return view(sessionId, snapshots.load(sessionId));
    }
    // 新增结束

    // 从这里开始是新增的：会话主动恢复接口。
    /**
     * 客户端（重新）进入面试页面时调用：把判定为"中断的异步步骤"幂等补跑完。
     *
     * <p>为什么要有这个接口：面试的异步链路（预生成下一题、生成报告）在某一步
     * 崩溃后不会倒退，但也不会自愈，用户刷新后可能卡在"一直转圈"。本接口读取当前
     * 快照状态，按状态触发对应的补跑（进行中重组下一题、已结束重组报告），
     * 全程幂等（见 SessionRecoveryService 类头注释），可反复调用。
     *
     * @param sessionId 面试会话 ID
     * @return 会话当前状态 + 本次实际补跑的步骤
     */
    @PostMapping("/{sessionId}/recover")
    public SessionRecoveryService.RecoveryResult recover(
            @PathVariable("sessionId") UUID sessionId
    ) {
        return recovery.recover(sessionId);
    }
    // 新增结束

    // 从这里开始是新增的：追问查询接口。
    /**
     * 查询某一道原题上已经生成的追问，供前端在"答完原题、取下一题之前"调用。
     *
     * <p>为什么必须有这个接口：追问存在 interview_follow_up 表，不作为新题插入
     * interview_question。前端取"下一题"走的是 POST /{sessionId}/questions，那里只会
     * 返回 AI 新出的题，永远看不到追问。少了这一问，候选人根本不知道还有追问要答，
     * 后端的 is_follow_up 链路在界面上就走不到。</p>
     *
     * <p>调用时机：前端提交完原题答案、轮询到状态回到 QUESTIONING 之后。
     * 此时追问生成链（judge → ASK_COMPLETED）已经跑完并落库，能稳定查到。</p>
     *
     * <p>为什么返回 null 而不是 404：没有追问是正常业务分支（分数段决定不追问），
     * 返回空响应体让前端用一次判空处理两种情况，不需要把"正常"写成"异常"。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 刚答完的那道原题 ID
     * @return 追问视图；这道题没有追问时返回 null（HTTP 200 + 空 body）
     */
    @GetMapping("/{sessionId}/follow-up")
    public FollowUpView followUp(@PathVariable("sessionId") UUID sessionId,
                                 @RequestParam("questionId") UUID questionId)
    {
        return followUps.find(sessionId, questionId);
    }
    // 新增结束

    @PostMapping("/{sessionId}/end")
    public SessionView end(
            @PathVariable("sessionId") UUID sessionId
    )
    {
        com.example.interview.domain.InterviewSessionSnapshot oldValue = snapshots.load(sessionId);
InterviewSessionStateMachine.TransitionResult result = machine.advance(oldValue, InterviewEvent.END, UUID.randomUUID().toString());
        save(oldValue, result.snapshot());
        // 第四步：同步更新数据库中的会话实体状态字段，保证数据表与快照一致。
        InterviewSessionEntity entity = sessionMapper.selectById(
                sessionId.toString()
        );
        if (entity != null) {
            entity.setState(result.snapshot().state().name());
            sessionMapper.updateById(entity);
        }

        // 第五步：返回更新后的 SessionView。
        return view(sessionId, result.snapshot());
    }


}
