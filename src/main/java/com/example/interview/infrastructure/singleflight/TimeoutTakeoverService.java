// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// ====================================================================
// TimeoutTakeoverService - AI 调用超时接管
// Singleflight 里的生产者失败且属于"可重试"时，不立刻重试，而是把任务
// 登记到这里；由 @Scheduled 定时扫描，等 TTL 过期后再统一重跑，
// 让重试压力错峰，避免多个超时任务在同一时刻集体打爆 AI 服务。
// ====================================================================
@Service
public class TimeoutTakeoverService {

    // 一条待接管任务：key 用于复用 Singleflight 去重，deadline 是允许重试的时间点。
    private record TakeoverTask(
            String key,
            Duration ttl,
            Supplier<String> producer,
            long deadline
    ) {
    }

    // 用 Singleflight 重跑任务而不是裸调 producer：重试也要走去重，避免重复调 AI。
    private final RedisSingleflight singleflight;
    // 内存任务表：key -> 待接管任务；会被扫描线程和注册线程并发访问，用 ConcurrentHashMap。
    private final Map<String, TakeoverTask> tasks =
            new ConcurrentHashMap<>();

    // 构造器注入 Singleflight；任务表是服务自己的状态，不需要外部提供。
    // 加 @Lazy：RedisSingleflight 与 TimeoutTakeoverService 互相依赖（前者持锁失败要登记接管，
    // 后者重跑又要复用前者的去重），构造器注入会形成循环依赖，Spring Boot 2.6 之后默认禁止，
    // 加 @Lazy 后这里先拿到一个代理对象，真正调用 execute 时才解析真身，循环即被打破。
    public TimeoutTakeoverService(
            @Lazy RedisSingleflight singleflight
    ) {
        this.singleflight = singleflight;
    }

    /**
     * 注册一个待接管任务：在给定 TTL 内如果没人完成该 key 的执行，
     * 调度器会在超时后主动重新执行 producer。
     *
     * <p>为什么注册而不是立即重试：AI 调用超时往往是因为网络抖动或服务
     * 瞬时过载，立刻重试大概率还是失败。先登记到内存 Map 并记录截止时间，
     * 由 @Scheduled 的扫描任务在 TTL 过期后统一重试，把重试压力错峰分散，
     * 避免多个超时任务在同一时刻集体打爆 AI 服务。
     *
     * @param key      与 RedisSingleflight 相同的业务 key，重试时复用其去重能力
     * @param ttl      接管期限，超过该时长仍无结果才允许重试
     * @param producer 真正执行 AI 调用的生产函数
     */
    public void register(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        // 同一 key 重复注册会覆盖旧任务：后登记的时间更近，以它为准。
        tasks.put(
                key,
                new TakeoverTask(
                        key,
                        ttl,
                        producer,
                        // 截止时间 = 当前时间 + TTL，扫描时用它判断有没有到期。
                        System.currentTimeMillis()
                                + ttl.toMillis()
                )
        );
    }

    /**
     * 每 5 秒扫描一次所有已注册任务，把已经到期的任务重新投递执行。
     *
     * <p>为什么用 removeIf 而不是 for 循环：removeIf 在遍历的同时删除满足
     * 条件的元素，语义与"执行完就移除"完全吻合，且 ConcurrentHashMap 的
     * removeIf 是线程安全的，不会在扫描过程中与 register 并发写入冲突。
     *
     * <p>重试本身走 singleflight.execute：即使重试时已有其他消费者抢先
     * 完成了该 key 的执行，execute 的去重逻辑会让这次重试直接拿到缓存结果，
     * 不会真的重复调用 AI。执行失败的任务保留在 Map 中等待下一个扫描周期。
     */
    @Scheduled(fixedDelay = 5000)
    public void takeOverExpired() {
        // 整轮扫描用同一个"现在"，判断到期的口径保持一致。
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            TakeoverTask task = entry.getValue();
            // 未到截止时间就暂不处理，留给后面的扫描周期。
            if (task.deadline() > now) {
                return false;
            }
            try {
                // 重跑走 singleflight.execute：别的消费者已完成也能直接拿结果，
                // value -> value 表示结果原样返回，不会再真的重复调一次 AI。
                singleflight.execute(
                        task.key(),
                        task.ttl(),
                        task.producer(),
                        value -> value
                );
            } catch (RuntimeException ignored) {
                // 重试仍然失败（例如 AI 持续不可用），保留任务等待下轮再试。
                return false;
            }
            // 执行成功：从任务表里移除，本轮不用再管它。
            return true;
        });
    }
}
// 新增结束
