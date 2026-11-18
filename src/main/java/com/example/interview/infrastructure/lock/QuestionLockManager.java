package com.example.interview.infrastructure.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 题级分布式锁：Redisson RLock。
 *
 * <h3>锁的粒度</h3>
 * key = {@code interview:answer:{sessionId}:{questionId}}，粒度是「会话 + 题目」。
 * 同一道题只能有一个请求在「写答案 + 推进状态 + 投任务」这段里，不同题目互不影响——
 * 粒度再粗（整个会话一把锁）会让一场面试的所有题串行，粒度再细（只锁请求）等于没锁。
 *
 * <h3>为什么用 Redisson，而不是手写 SET NX EX + UUID + 比对删除</h3>
 * 一把正确的分布式锁要同时满足三件事：互斥、防死锁、防误删别人的锁。
 * 手写版可以做到，但每一件都要自己保证正确性：
 * <ul>
 *   <li>过期时间设多长？设短了业务没跑完锁就没了，设长了进程崩了要等很久</li>
 *   <li>释放时怎么确认「这把锁还是我的」？先 GET 再 DEL 之间有时间窗，要上 Lua 才能原子</li>
 *   <li>业务被 GC 停顿拖长怎么办？没人续期，锁照样会在跑一半时过期</li>
 * </ul>
 * 这三件事 Redisson 全套做好：看门狗按租约 1/3 自动续期，unlock 内部用 Lua 校验持有者。
 * 手写等于重新实现一套更差的，没有任何收益。
 *
 * <h3>看门狗为什么在这里有价值</h3>
 * 本项目的锁内只有「写答案 + 推进状态 + 投消息」，正常几十毫秒，本来离 30 秒租约很远。
 * 但看门狗兜住的是「万一」：一次长 GC、一次网络抖动、一台机器负载飙高，
 * 只要进程还活着、业务线程还在跑，锁就一直续着，不会被别的请求抢走；
 * 进程真崩了，看门狗跟着死，锁 30 秒后自然过期，不会永久锁死。
 */
@Component
public class QuestionLockManager {

    private final RedissonClient redisson;

    public QuestionLockManager(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * 尝试获取锁，不等待：抢不到立刻返回失败，调用方直接拒绝请求。
     *
     * <p>为什么不等待：同一道题并发提交两次，本质是前端重复点击或网络重试，
     * 让第二个请求排队等 3 秒再失败，用户只会觉得「卡了很久然后报错」。
     * 立刻拒绝 + 明确的「正在处理中」提示，体验和语义都更对。
     *
     * @param key 锁键（interview:answer:{sessionId}:{questionId}）
     * @return 句柄；{@code acquired=false} 表示锁已被占用
     */
    public LockHandle tryLock(String key) {
        RLock lock = redisson.getLock(key);
        boolean acquired;
        try {
            // waitTime=0：抢不到立刻返回，不排队
            // leaseTime=-1：不指定固定租约 → 启用看门狗，每 1/3 租约自动续期
            acquired = lock.tryLock(0, -1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 线程被中断：恢复中断标记并当作抢锁失败，绝不吞掉中断信号
            Thread.currentThread().interrupt();
            acquired = false;
        }
        return new LockHandle(lock, acquired);
    }

    /**
     * 锁句柄：AutoCloseable 让调用方用 try-with-resources 保证释放。
     *
     * <p>释放前判 {@code isHeldByCurrentThread}：如果锁因为极端情况（Redisson 客户端重连）
     * 已经不属于本线程，直接 unlock 会抛 IllegalMonitorStateException，
     * 而这个异常发生在业务成功的返回路径上，会把一个已经成功的请求打成 500。
     */
    public record LockHandle(RLock lock, boolean acquired) implements AutoCloseable {
        @Override
        public void close() {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
