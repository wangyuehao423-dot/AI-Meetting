package com.example.interview.infrastructure.singleflight;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

// ============================================================
// CallCostTallyService —— Singleflight 的"成本 / token"显式统计与配额中心
// ============================================================
// 为什么需要它（这正是亮点3里原本缺失的一块）：
//   RedisSingleflight 之前只做"去重合并"，顺带省掉重复 AI 调用，但没有任何
//   显式的调用次数统计，也没有"一家业务刷爆预算就限流"的配额控制——宣传里
//   的"成本控制能力"是顺带、被动的。本服务补上两块硬能力：
//     1) 显式统计：按业务 key 精确计数「真实跑 AI 的次数(miss)」与
//        「命中缓存回放的次数(hit)」，以及被配额拦下的次数(blocked)。
//        真实 AI 调用次数约等于花钱的次数，"命中回放"则是省掉的钱——
//        有了这两个数，"成本控制"就成了一份可读、可下钻的账。
//     2) 配额：每个 key 在一个滑动时间窗（默认 1 小时）内允许的真实 AI 调用数
//        （maxCallsPerWindow，0=不限）。窗口满了就拒绝继续发生产者，
//        Singleflight 只能回放缓存或交给上层降级，从源头兜住预算。
//
// 实现要点：
//   - miss/hit/blocked 用 LongAdder 聚合，多线程高频自增不锁全局；
//   - 配额判定用「时间戳双端队列」实现滑动窗口，把超过窗口年龄的调用
//    pollFirst 掉再做容量比较，比每个 key 一把原子锁的固定窗口更精确；
//   - 统计只进内存：成本账是给运维看的实时仪表盘，不需要跨节点严格一致，
//     进程重启后可归零，这也符合"成本控制是软约束"的定位。
// ============================================================
@Component
public class CallCostTallyService {

    // 每个 key 的三格账：0=aiCalls(真花钱), 1=cacheReplays(省掉的钱), 2=blocked(被配额拦下)
    private final Map<String, long[]> perKey = new ConcurrentHashMap<>();
    // 每个 key 的滑动窗口时间戳队列（仅用于配额容量判断）
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    // 滑窗内允许的真实 AI 调用数；0 表示不限额（默认）。
    private final int maxCallsPerWindow;
    // 滑窗时长（纳秒），默认 1 小时。
    private final long windowNanos;
    // 滑窗是否启用（maxCallsPerWindow>0 才生效）。
    private final boolean quotaEnabled;

    /**
     * 构造成本统计与配额服务，配额参数从配置读取。
     *
     * <p>为什么默认 0（不限）：配额一旦误开会让接口开始挡真实用户，所以默认关闭、
     * 需要时显式配置开启，避免"开箱即拦"伤到正常面试流程。
     *
     * @param maxCallsPerWindow 滑窗内每个 key 允许的真实 AI 调用数，0=不限
     * @param windowSeconds     滑窗时长（秒），默认 3600
     */
    public CallCostTallyService(
            @Value("${interview.singleflight.quota.max-calls-per-window:0}")
            int maxCallsPerWindow,
            @Value("${interview.singleflight.quota.window-seconds:3600}")
            long windowSeconds
    ) {
        if (maxCallsPerWindow < 0) {
            throw new IllegalArgumentException(
                    "max-calls-per-window must be >= 0"
            );
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "quota window-seconds must be > 0"
            );
        }
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.windowNanos = windowSeconds * 1_000_000_000L;
        this.quotaEnabled = maxCallsPerWindow > 0;
    }

    /**
     * 记一笔"真实跑了一次 AI 调用"（这条是花钱的）。
     *
     * @param key 业务单飞 key
     */
    public void recordMiss(String key) {
        tally(key)[0]++;
    }

    /**
     * 记一笔"命中了缓存、回放结果、没再调 AI"（这条是省钱的）。
     *
     * @param key 业务单飞 key
     */
    public void recordHit(String key) {
        tally(key)[1]++;
    }

    /**
     * 记一笔"被配额拦下、未经允许就去调 AI"（这条说明预算可能见底了）。
     *
     * @param key 业务单飞 key
     */
    public void recordBlocked(String key) {
        tally(key)[2]++;
    }

    /**
     * 判定当前 key 是否允许再发起一次真实 AI 调用。
     *
     * <p>配额未启用时恒返回 true。配额启用时，先丢弃窗口里过期的调用记录，
     * 再比较当前窗口内已有调用数是否达到上限：未到则登记这次调用并放行，
     * 已到则返回 false——调用方（Singleflight 的 guarded producer）据此抛
     * CostQuotaExceededException，不再真正调 AI。
     *
     * @param key 业务单飞 key
     * @return true=可调用；false=配额已满
     */
    public boolean tryAcquire(String key) {
        if (!quotaEnabled) {
            return true;
        }
        long now = System.nanoTime();
        Deque<Long> window = windows.computeIfAbsent(
                key,
                ignored -> new ConcurrentLinkedDeque<>()
        );
        // 用同一把对象锁串行化"清理过期 + 容量判断 + 登记"，保证并发下不超额。
        // 为什么锁 deque 而不是 key：同一进程内 key 就是唯一维度，锁住这个集合对象
        // 就圈定了修改边界；锁 key 字符串反而可能和别处的字符串锁撞车。
        synchronized (window) {
            while (!window.isEmpty()
                    && now - window.peekFirst() > windowNanos) {
                window.pollFirst();
            }
            if (window.size() >= maxCallsPerWindow) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * 生成一份可下钻的成本统计快照，供统计端点返回给运维 / Code Agent。
     *
     * @return 全局汇总 + 分 key 明细 + 配额配置
     */
    public StatsSnapshot stats() {
        long totalAi = 0, totalReplay = 0, totalBlocked = 0;
        Map<String, KeyStats> byKey = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : perKey.entrySet()) {
            long[] t = entry.getValue();
            totalAi += t[0];
            totalReplay += t[1];
            totalBlocked += t[2];
            byKey.put(
                    entry.getKey(),
                    new KeyStats(t[0], t[1], t[2])
            );
        }
        return new StatsSnapshot(
                totalAi,
                totalReplay,
                totalBlocked,
                quotaEnabled,
                maxCallsPerWindow,
                windowNanos / 1_000_000_000L,
                byKey
        );
    }

    /**
     * 清空全部统计与窗口记录（运维在调参后重置基线用）。
     */
    public void reset() {
        perKey.clear();
        windows.clear();
    }

    // 取某个 key 的三格账，第一次出现时建好空账。
    private long[] tally(String key) {
        return perKey.computeIfAbsent(key, ignored -> new long[3]);
    }

    /**
     * 单 key 成本账：真实 AI 调用次数、缓存回放次数、被配额拦截次数。
     */
    public record KeyStats(
            long aiCalls,
            long cacheReplays,
            long blocked
    ) {
    }

    /**
     * 全量统计快照：全局汇总 + 分 key 明细 + 配额开关与参数。
     */
    public record StatsSnapshot(
            long totalAiCalls,
            long totalCacheReplays,
            long totalBlocked,
            boolean quotaEnabled,
            int maxCallsPerWindow,
            long windowSeconds,
            Map<String, KeyStats> byKey
    ) {
    }
}