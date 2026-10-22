// 从这里开始是修改的：以下整个文件由"同步版（POST 收 ReportContext、在 HTTP 线程里等 AI 返回）"
// 改写为"异步三接口版（触发生成 / 轮询状态 / 读取正文）"，并补上会话归属校验。
package com.example.interview.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 报告 Controller。
 * 
 * 提供报告生成、状态查询和内容获取接口。
 * 生成操作是异步的，POST 接口立即返回，结果通过状态接口轮询获取。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    // 报告视图：把"状态 + 内容"打包一起返回前端。
    // markdown 只在 status=COMPLETED 时才有值，其它状态下是 null，
    // 前端据此决定是"直接渲染报告"还是"继续轮询 /status"。
    public record ReportView(
            UUID sessionId,
            String status,
            String markdown
    ) {
    }

    // 报告表 Mapper：查询 / 插入 / 更新 report 记录。
    private final ReportMapper reports;
    // 报告生成异步服务：generate 上带 @Async，调用即返回，不占用 HTTP 线程。
    private final ReportGenerationAsyncService generation;
    // 会话表 Mapper：只用来查会话归属，做"这份报告是不是你的"权限校验。
    private final InterviewSessionMapper sessions;
    // 当前登录用户提供者：从 JWT 解析出 userId，供所有者校验使用。
    private final CurrentUserIdProvider currentUser;

    // 构造器注入四个协作者，字段全是 final，Controller 保持无状态、线程安全。
    public ReportController(
            ReportMapper reports,
            ReportGenerationAsyncService generation,
            InterviewSessionMapper sessions,
            CurrentUserIdProvider currentUser
    ) {
        this.reports = reports;
        this.generation = generation;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /**
     * 触发报告生成（异步）。
     *
     * <p>这里只做三件事：校验归属、把记录状态置成 GENERATING、把任务丢给
     * ReportGenerationAsyncService。真正的上下文组装和 AI 调用在独立线程池里跑，
     * 接口不会被几十秒的模型耗时拖住，所以是"立即受理 + 前端轮询进度"的模型。
     *
     * <p>为什么要"先查再插"而不是无脑 insert：用户在生成过程中重复点"生成报告"
     * 时，POST 应当幂等——报告记录按 session_id 唯一，已存在就把状态和内容重置
     * 后重新生成，而不是插出第二条重复记录。
     *
     * @param sessionId 面试会话 ID
     * @return 刚创建 / 重置后的报告视图，此时 markdown 一定为 null
     */
    @PostMapping("/{sessionId}")
    public ReportView generate(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 权限校验放在 Controller 层：异步线程里没有请求上下文，
        // 取不到当前用户，所以必须在这里先把"是不是本人会话"挡掉。
        assertOwner(sessionId);
        // 按 session_id 查报告记录：报告与会话一对一，查出来就是"当前这份报告"。
        ReportEntity entity = reports.selectOne(
                new QueryWrapper<ReportEntity>()
                        // 用字符串比较：库里 session_id 存的是 varchar，不是二进制 UUID。
                        .eq("session_id", sessionId.toString())
        );
        if (entity == null) {
            // 从这里开始是新增的：并发安全地创建报告记录。
            // 为什么会并发：报告页一进入会同时触发两次 loadReport（useEffect 一次、
            // finishInterview() 里再 await 一次），两处都会 POST /reports；后端又是
            // "先查再插"，两个线程可能都先查到"无记录"，随后都想 insert 一条。
            // unique(session_id) 只允许一条成功，另一条会抛 DuplicateKeyException，
            // 让接口直接 500 —— 前端把这次 500 静默吞掉后继续轮询，界面就一直停在
            // "正在生成"出不来。所以这里把创建包起来：插入冲突就回查出已存在的记录，
            // 统一走下面的"重置状态 + 重新生成"分支，保证并发触发幂等、绝不 500。
            entity = new ReportEntity();
            entity.setSessionId(sessionId.toString());
            entity.setStatus("GENERATING");
            try {
                reports.insert(entity);
            } catch (DuplicateKeyException duplicate) {
                // 另一并发请求已抢先插入：把这条记录重新查出来继续走生成；
                // 若查不到（行被并发删除的极端情况），恢复抛出，避免拿着 null 往下走到 NPE。
                entity = reports.selectOne(
                        new QueryWrapper<ReportEntity>()
                                .eq("session_id", sessionId.toString())
                );
                if (entity == null) {
                    throw duplicate;
                }
                entity.setStatus("GENERATING");
                entity.setContent(null);
                reports.updateById(entity);
            }
            // 新增结束
        } else {
            // 重新生成：状态回到 GENERATING，同时清空旧内容，
            // 避免前端在生成过程中还翻到上一版报告。
            entity.setStatus("GENERATING");
            entity.setContent(null);
            reports.updateById(entity);
        }
        // 触发异步生成：@Async 让这次调用立刻返回，HTTP 线程不等待 AI。
        generation.generate(sessionId, entity.getId());
        // 同步返回"已受理"的状态，此刻 content 必然是 null。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    /**
     * 查询报告生成状态。
     *
     * <p>前端在发起生成后轮询这个接口，直到 status 变成 COMPLETED 或 FAILED。
     * 它只读记录、不触发任何计算，所以很轻，可以承受高频轮询。
     *
     * @param sessionId 面试会话 ID
     * @return 当前状态（GENERATING / COMPLETED / FAILED）与已完成的内容
     */
    @GetMapping("/{sessionId}/status")
    public ReportView status(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 同样先校验归属：状态接口也不能让用户查到别人的报告。
        assertOwner(sessionId);
        ReportEntity entity = find(sessionId);
        // 状态和内容一起返回：COMPLETED 时前端能一把拿到正文，省掉再调一次 get。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    /**
     * 查询报告内容。
     *
     * <p>为什么内容未完成时要返回 null、而不是把 GENERATING 的半成品吐出去：
     * content 只有异步任务写完全才会落库，没完成就返回空，
     * 前端看到 null 就知道该去轮询 /status，而不是当成"报告就是空的"。
     *
     * @param sessionId 面试会话 ID
     * @return COMPLETED 时返回正文，否则 markdown 为 null
     */
    @GetMapping("/{sessionId}")
    public ReportView get(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 权限校验：报告属于会话，会话属于用户，不能跨用户读取。
        assertOwner(sessionId);
        ReportEntity entity = find(sessionId);
        if (!"COMPLETED".equals(entity.getStatus())) {
            // 还没生成完：只回状态、不回内容，避免前端拿到半截数据。
            return new ReportView(
                    sessionId,
                    entity.getStatus(),
                    null
            );
        }
        // 已完成：把 Markdown 正文一起返回。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    // ---- 私有方法 ---- //

    /**
     * 按会话 ID 查出报告记录，查不到直接抛异常。
     *
     * <p>把"查不到"统一收敛在这里，status / get 两个接口就不用各自写一遍判空；
     * 抛 IllegalArgumentException 是为了交给全局异常处理器映射成 404。
     *
     * @param sessionId 面试会话 ID
     * @return 该会话对应的报告记录
     */
    private ReportEntity find(UUID sessionId) {
        ReportEntity entity = reports.selectOne(
                new QueryWrapper<ReportEntity>()
                        .eq("session_id", sessionId.toString())
        );
        if (entity == null) {
            // 记录不存在：可能是 sessionId 编造的，按"资源不存在"处理。
            throw new IllegalArgumentException(
                    "report not found"
            );
        }
        return entity;
    }

    /**
     * 校验当前用户是否为会话的所有者。
     *
     * <p>为什么必须有这一步：报告里有面试问答原文，属于敏感数据，
     * 只要把 sessionId 换成别人的就能读到别人的报告。
     * 所以三个接口进门第一句都是 assertOwner。
     *
     * <p>为什么从 CurrentUserIdProvider 取用户而不是从请求参数取：
     * 请求参数里的 userId 可以伪造，只有 JWT 过滤器验签后写入的才是可信的。
     *
     * @param sessionId 要操作的面试会话 ID
     */
    private void assertOwner(UUID sessionId) {
        InterviewSessionEntity session =
                sessions.selectById(sessionId.toString());
        if (session == null) {
            // 会话都不存在，说明 sessionId 非法，按"资源不存在"处理。
            throw new IllegalArgumentException(
                    "interview session not found"
            );
        }
        // session.getUserId() 是 Long，与 currentUserId() 的 long 比较会自动拆箱做数值比较
        // （不是对象引用比较），所以这里不存在"超出 Long 缓存范围就判错"的问题。
        if (session.getUserId() != currentUser.currentUserId()) {
            // 会话属于别人：拒绝访问，由 Spring Security 统一映射成 403。
            throw new AccessDeniedException(
                    "session does not belong to current user"
            );
        }
    }
}
// 修改结束
