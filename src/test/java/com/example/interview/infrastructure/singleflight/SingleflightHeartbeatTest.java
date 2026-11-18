package com.example.interview.infrastructure.singleflight;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 心跳机制的两个核心承诺，用真实 Redis 跑出来：
 *
 * <ol>
 *   <li>执行方慢（12 秒），等待方不能把它误判成死亡——心跳一直在续，等待方继续等并拿到结果；</li>
 *   <li>执行方死（心跳停了 20 秒），等待方必须快速接管，而不是盲等满 30 秒。</li>
 * </ol>
 *
 * 说明：测试里用线程手动调用 beatInFlight，代替 Spring 的 @Scheduled（本测试不起 Spring 容器，
 * 只验证心跳逻辑本身；调度注册由 @Scheduled 注解在运行时完成）。
 */
class SingleflightHeartbeatTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static SingleflightStateMachine stateMachine;
    private static RedisSingleflight singleflight;
    private static ScheduledExecutorService scheduler;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory("localhost", 16379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        stateMachine = new SingleflightStateMachine(redis);
        CallCostTallyService cost = new CallCostTallyService(0, 3600);
        FailureClassifier classifier = new FailureClassifier();
        // 接管服务在这里只当"登记表"用：本测试不启动它的调度线程。
        TimeoutTakeoverService takeover = new TimeoutTakeoverService(null);
        singleflight = new RedisSingleflight(
                redis,
                true,
                classifier,
                takeover,
                stateMachine,
                cost
        );

        // 每 500ms 打一拍，等价于线上 @Scheduled(fixedDelay = 2000) 的效果（更密，便于观察）。
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> singleflight.beatInFlight(),
                0, 500, TimeUnit.MILLISECONDS
        );
    }

    @AfterAll
    static void tearDown() {
        scheduler.shutdownNow();
        factory.destroy();
    }

    @Test
    void slow_executor_is_not_killed_by_waiter() throws Exception {
        String key = "probe:beat:" + UUID.randomUUID();
        // 执行方：跑 12 秒。没有心跳的话，等待方会在 8 秒后误判它死了。
        CompletableFuture<String> owner = CompletableFuture.supplyAsync(() ->
                singleflight.execute(
                        key,
                        Duration.ofMinutes(1),
                        () -> {
                            sleep(12_000);
                            return "slow-result";
                        },
                        Function.identity()
                )
        );

        // 1 秒后另一个请求进来，走等待分支；它必须一直等下去，而不是 8 秒后抛"心跳丢失"。
        sleep(1_000);
        CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() ->
                singleflight.execute(
                        key,
                        Duration.ofMinutes(1),
                        () -> "should-not-be-called",
                        Function.identity()
                )
        );

        // 观察窗口 10 秒（跨过 8 秒阈值），期间心跳年龄应始终小于 3 秒。
        long maxAge = 0;
        for (int i = 0; i < 100; i++) {
            sleep(100);
            long age = stateMachine.heartbeatAgeMs(key);
            if (age >= 0) {
                maxAge = Math.max(maxAge, age);
            }
        }
        assertTrue(
                maxAge > 0 && maxAge < 3_000,
                "心跳应持续续期，最大年龄应远小于 8 秒，实际 " + maxAge + "ms"
        );
        assertEquals("slow-result", owner.get(20, TimeUnit.SECONDS));
        assertEquals("slow-result", waiter.get(20, TimeUnit.SECONDS));
    }

    @Test
    void dead_executor_is_taken_over_quickly() {
        String key = "probe:dead:" + UUID.randomUUID();
        // 伪造现场：别人持有锁、状态 RUNNING，但心跳停在 20 秒前（进程已被 kill）。
        redis.opsForHash().put("sfs:state:" + key, "state", "RUNNING");
        redis.opsForHash().put(
                "sfs:state:" + key,
                "hb",
                Long.toString(System.currentTimeMillis() - 20_000)
        );
        redis.opsForValue().set(
                "singleflight:lock:" + key,
                "other-owner",
                Duration.ofSeconds(30)
        );

        long start = System.currentTimeMillis();
        assertThrows(
                RetryableSingleflightException.class,
                () -> singleflight.execute(
                        key,
                        Duration.ofMinutes(1),
                        () -> "should-not-run",
                        Function.identity()
                )
        );
        long elapsed = System.currentTimeMillis() - start;
        // 关键指标：比原来盲等 30 秒快一个数量级。
        assertTrue(
                elapsed < 2_000,
                "心跳丢失后应在 2 秒内接管，实际耗时 " + elapsed + "ms"
        );
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
