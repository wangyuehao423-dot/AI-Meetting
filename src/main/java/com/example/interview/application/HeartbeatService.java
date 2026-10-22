// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// 后台任务心跳服务
// ============================================================
// 职责：把"后台异步任务（评分 / 追问生成 / 预生成）还在不在跑"变成一个可查询的信号。
//   1) 任务开始：register(sessionId) —— 放进进程内活跃表，并立刻写一次心跳
//   2) 运行期间：@Scheduled 每 10 秒给活跃表里的每个会话续一次心跳（TTL 30 秒）
//   3) 任务结束：unregister(sessionId) —— 摘掉活跃项 + 删掉心跳 key
//   4) 补偿器：exists(sessionId) —— 反查"处理这条任务的线程是否还活着"
//
// 为什么需要它：补偿器原来只能靠"队列里还有没有这条任务"判断，而这个判断对
//   "任务已被消费者取走、线程随后崩溃"完全失效——记录会永远卡在处理中状态。
//
// 为什么用"定时器续期"而不是"任务每次干完活自己写一次心跳"：
//   线程崩溃时，那段写在业务代码里的心跳代码根本不会被执行；
//   @Scheduled 跑在独立的调度线程上，业务线程死了它照样在续期，
//   30 秒 TTL 一到心跳自然消失——这正好就是"线程已死"的信号。
// ============================================================
@Service
public class HeartbeatService {

    // 心跳就是一个带 TTL 的字符串 key，StringRedisTemplate 足够用，
    // 不需要自定义序列化器，也不会把一个对象图塞进 Redis。
    private final StringRedisTemplate redis;

    // 活跃会话表：定时器靠它知道该给哪些会话续心跳。
    // 为什么用 ConcurrentHashMap：写入的是业务线程（register / unregister），
    // 遍历的是调度线程（heartbeat），两边天然并发。
    // 为什么不放进 Redis：活跃会话是"本进程正在跑哪些任务"，是本地概念；
    // 放 Redis 反而需要在进程退出时清理，做不好会留下永远续期的僵尸 key。
    private final ConcurrentHashMap<UUID, Boolean> activeSessions =
            new ConcurrentHashMap<>();

    // 构造器只注入 Redis：心跳服务没有别的协作者，依赖越少越不容易出错。
    public HeartbeatService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 注册会话心跳（由后台任务在开始时调用）。
     *
     * <p>为什么注册时就写一次：如果只等下一次定时器触发才第一次写，
     * "注册完成 → 定时器下次触发"这段空窗期内 exists() 会返回 false，
     * 补偿器可能把刚启动的正常任务误判成已死线程。
     *
     * @param sessionId 面试会话 ID
     */
    public void register(UUID sessionId) {
        // 记入活跃表：定时器靠它才知道要给哪些会话续心跳。
        activeSessions.put(sessionId, Boolean.TRUE);
        // 立刻手写一次，消除"注册完到下一次定时器触发"之间的心跳空窗。
        write(sessionId);
    }

    /**
     * 注销会话心跳（由后台任务在 finally 块中调用）。
     *
     * <p>为什么必须放在 finally 里：任务可能抛异常提前退出，
     * 写在正常路径上的注销根本走不到，心跳会一直续到进程结束，
     * 让补偿器永远认为"还有人在处理这条任务"。
     *
     * @param sessionId 面试会话 ID
     */
    public void unregister(UUID sessionId) {
        // 先从活跃表摘掉：定时器不再为它续期，心跳不会再被写活。
        activeSessions.remove(sessionId);
        // 再主动删掉 key，让补偿器马上就能判定"处理线程已退出"，
        // 而不是干等 30 秒 TTL 自然过期。
        redis.delete(key(sessionId));
    }

    /**
     * 定时续期：每 10 秒为所有活跃会话写入一次 Redis 心跳。
     *
     * <p>为什么用 fixedDelay 而不是 fixedRate：fixedDelay 是"上一轮执行完再等 10 秒"，
     * fixedRate 是"固定频率触发"。若某轮写入卡住（Redis 抖动），fixedRate 会把
     * 后续轮次不断堆叠进来；fixedDelay 天然串行，不会堆积。
     *
     * <p>为什么 TTL 取 30 秒（= 3 个续期周期）：留出 20 秒的容错窗口。
     * 心跳 key 在 30 秒后消失，说明这个线程至少连续 20 秒没能完成写入，
     * 基本可以判定已经崩溃或卡死。
     */
    @Scheduled(fixedDelay = 10_000)
    public void heartbeat() {
        // 遍历活跃表逐个续期：keySet() 是弱一致视图，遍历期间有会话被
        // remove 掉也不会抛 ConcurrentModificationException。
        activeSessions.keySet().forEach(this::write);
    }

    /**
     * 检查会话心跳是否存在（由 JudgeTaskCompensationService 调用）。
     *
     * @param sessionId 面试会话 ID
     * @return true = 心跳还在（处理线程存活）；false = 心跳已消失（处理线程死亡）
     */
    public boolean exists(UUID sessionId) {
        // 包一层 Boolean.TRUE.equals：hasKey 的返回值是包装类型 Boolean，
        // 在连接异常等情况下可能为 null，直接拆箱会抛 NPE；
        // 这里把 null 统一当成"心跳不存在"，宁可多补偿一次也不要让调用方崩掉。
        return Boolean.TRUE.equals(
                redis.hasKey(key(sessionId))
        );
    }

    /**
     * 写入心跳到 Redis。
     *
     * <p>为什么值是当前时间戳：业务上不读这个值，但存时间戳让排查变简单——
     * 线上直接 GET heartbeat:{sessionId} 就能看到最后一次续期时间，
     * 从而算出线程大概死了多久。
     */
    private void write(UUID sessionId) {
        // SET heartbeat:{sessionId} <当前毫秒时间戳> EX 30：
        // 真正的信号是"这个 key 还在不在"，值只是给人看的。
        redis.opsForValue().set(
                key(sessionId),
                Instant.now().toEpochMilli() + "",
                Duration.ofSeconds(30)
        );
    }

    // 心跳 key 的拼装集中在一处：写、查、删都调它，避免前缀写歪对不上。
    private String key(UUID sessionId) {
        // 形如 heartbeat:{sessionId}，出问题时可以直接 GET 这个 key 看最后心跳时间。
        return "heartbeat:" + sessionId;
    }
}
// 新增结束
