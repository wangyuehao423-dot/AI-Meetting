package com.example.interview.infrastructure.singleflight;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
// 声明为 Spring 组件：RedisSingleflight 被 InterviewAiFacade、QuestionController、
// FollowUpGenerationService 等多处构造器注入，必须注册为 Bean 才能启动；
// 同时类内的 clearExpiredLocalCache 依赖 @Scheduled，也需要 Bean 化后才会被定时调用。
@Component
public class RedisSingleflight {
    // Redis 中缓存 AI 结果的前缀，用于快速定位已生成的内容。
    private static final String VALUE_PREFIX =
            "singleflight:value:";
    // Redis 中分布式锁的前缀，用于防止多个节点同时执行 producer。
    private static final String LOCK_PREFIX =
            "singleflight:lock:";
    // Redis 中围栏令牌的前缀，用于单调递增地标记每次评分结果的新旧顺序。
    private static final String FENCE_PREFIX =
            "singleflight:fence:";

    
    // 本地缓存ai出题结果
    private final ConcurrentHashMap<
            String,
            CompletableFuture<String>
            > localCache = new ConcurrentHashMap<>();
    // 缓存结果过期时间
    private final ConcurrentHashMap<String, Long> localExpiry =
            new ConcurrentHashMap<>();

    // 存结果的版本号，防止乱修改
    private final ConcurrentHashMap<String, Long> localFenceTokens =
            new ConcurrentHashMap<>();

    // Spring Data Redis 的操作模板，用于执行 Redis 命令和 Lua 脚本。
    private final StringRedisTemplate redis;
    private final FailureClassifier failureClassifier;
    // 超时接管服务：可重试的失败登记给它，TTL 过期后由它统一重跑。
    private final TimeoutTakeoverService takeover;
    // 是否启用本地降级开关，从配置文件中读取，默认为 true。
    private final boolean localFallbackEnabled;
    private final SingleflightStateMachine stateMachine;
    // 从这里开始是新增的：成本统计与配额中心。
    // 为什么 Singleflight 要挂它：要兑现"成本控制能力"必须有人记账——真实 AI 调用
    // 是花钱的(miss)，命中缓存回放是省钱的(hit)，被配额拦下是预算见底的信号(blocked)。
    // 所有"真正调用 producer"的路径（Redis 路径 runProducer、本地路径 first supplier）
    // 最终都汇聚在 execute 里那个 guarded 包装上，在这一个点就能精确记账 + 限流。
    private final CallCostTallyService cost;
    // 新增结束
    public RedisSingleflight(
            // Spring Data Redis 的操作模板，由 Spring 自动注入。
            StringRedisTemplate redis,
            // 本地降级开关，从配置项 interview.singleflight.local-fallback-enabled 读取，默认 true。
            @Value(
                    "${interview.singleflight.local-fallback-enabled:true}"
            )
            boolean localFallbackEnabled,
            FailureClassifier failureClassifier,
            TimeoutTakeoverService takeover,
            SingleflightStateMachine stateMachine,
            // 从这里开始是新增的：构造器末尾追加成本统计服务（Spring 按类型注入，
            // 追加在末尾不会影响既有调用方）。
            CallCostTallyService cost
            // 新增结束
    ) {
        this.redis = redis;
        this.localFallbackEnabled = localFallbackEnabled;
        this.failureClassifier = failureClassifier;
        this.takeover = takeover;
        this.stateMachine = stateMachine;
        this.cost = cost;
    }
    // 先探测 Redis；探测失败时才进入本地降级分支。
    // 参数 key：业务唯一标识（如 question:sessionId:sequence），用于合并相同请求。
    // 参数 ttl：结果的过期时间，超过此时间后需要重新生成。
    // 参数 producer：实际执行 AI 调用的生产函数，只有第一个请求才会执行它。
    // 参数 converter：将 producer 返回的字符串转换为业务需要的类型 T。
    // 返回值：类型 T 的业务结果。
    public <T> T execute(
            String key,
            Duration ttl,
            Supplier<String> producer,
            Function<String, T> converter
    ) {
        stateMachine.register(key);
        // 从这里开始是新增的：把原来的 producer 包一层"配额看守"。
        // 为什么必须在 execute 最前面统一包、而不是分别包到 executeRedis / executeLocal：
        //   · 配额判定要在"真正调 AI"看门——两条路径最终都会执行 supplier，
        //     在唯一入口包一次，就能同时罩住 Redis 路径与本地降级路径，不会漏记或重复记；
        //   · recordMiss 记的是"真的花钱的那次调用"，与是否缓存命中是互斥的两个账，
        //     这样 stats 里的 aiCalls 才约等于预算消耗。缓存的命中则在下游分支单独 recordHit。
        Supplier<String> guarded = () -> {
            if (!cost.tryAcquire(key)) {
                cost.recordBlocked(key);
                throw new CostQuotaExceededException(key);
            }
            cost.recordMiss(key);
            return producer.get();
        };
        // 新增结束
        // 第一步：检查 Redis 是否可用。
        if (!redisAvailable()) {
            // 第二步：Redis 不可用时，判断是否允许本地降级。
            if (!localFallbackEnabled) {
                // 降级被禁用则抛出异常，防止无限制地穿透到 AI。
                throw new IllegalStateException(
                        "Redis is unavailable and local fallback is disabled"
                );
            }
            // 第三步：执行本地请求合并，默认过期时间为 5 分钟。
            return converter.apply(
                    executeLocal(
                            key,
                            Duration.ofMinutes(5),
                            guarded
                    )
            );
        }

        // Redis 可用时走分布式请求合并路径。
        return converter.apply(
                executeRedis(key, ttl, guarded)
        );
    }
    public FencedValue executeFenced(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        // 先通过统一的 execute 方法获取执行结果。
        String value = execute(
                key,
                ttl,
                producer,
                Function.identity()
        );
        String tokenKey = FENCE_PREFIX + key;
        Long token;
        // 根据 Redis 是否可用，选择分布式递增或本地递增令牌。
        if (redisAvailable()) {
            // Redis 可用时使用 Redis 的 INCR 命令保证全局单调递增。
            token = redis.opsForValue().increment(tokenKey);
        } else {
            // Redis 不可用时使用本地 ConcurrentHashMap 保证单调递增。
            // compute 对同一个 key 是原子操作，避免并发请求拿到相同的本地令牌。
            token = localFenceTokens.compute(
                    key,
                    (ignored, current) -> (current == null ? 0L : current) + 1L
            );
        }
        return new FencedValue(value, token == null ? 0L : token);
    }

    

    public boolean acceptFence(String key, long token) {
        if (!redisAvailable()) {
            // Redis 不可用时，从本地令牌映射中读取当前最新令牌进行比较。
            Long current = localFenceTokens.get(key);
            return current == null || token >= current;
        }
        // Redis 可用时，从 Redis 中读取最新令牌进行比较。
        String raw = redis.opsForValue().get(FENCE_PREFIX + key);
        return raw != null && Long.parseLong(raw) == token;
    }

    // executeRedis 是 Redis 可用时的分布式请求合并实现。它把“检查缓存是否命中”和
    // “尝试获取分布式锁”这两步用一个 Lua 脚本打包成一次原子操作提交给 Redis，从而彻底
    // 避免两个请求同时拿到“未命中”的结论、进而出现两个节点同时调用 AI 的竞态问题。
    // 脚本只会返回三种结果：CACHED（缓存命中，直接读缓存）；OWNER（成功抢到锁，是这一组
    // 请求的第一个，应当执行 producer 并把结果写回缓存）；WAIT（没命中也没抢到锁，需要轮询
    // 等待持有锁的那个请求把结果写进缓存）。对于 WAIT 分支，最多轮询 30 秒，若超时仍未拿到
    // 结果，则视为持锁方异常，由当前请求自己兜底调用 producer，避免系统被锁问题永久卡死。
    // 参数 key：业务唯一标识。
    // 参数 ttl：结果在 Redis 中的过期时间。
    // 参数 producer：实际执行 AI 调用的生产函数。
    // 返回值：AI 生成的字符串结果；可能来自缓存命中，也可能来自当前请求自己执行 producer。
    private String executeRedis(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        String valueKey = VALUE_PREFIX + key;
        String lockKey = LOCK_PREFIX + key;
        // 使用系统纳秒时间作为锁所有者标识，具备足够随机性。
        String owner = Long.toHexString(
                System.nanoTime()
        );
        // Lua 脚本：原子地执行"查缓存 → 抢锁"两步操作。
        // KEYS[1] = valueKey（缓存键）, KEYS[2] = lockKey（锁键）
        // ARGV[1] = owner（锁所有者）, ARGV[2] = "30"（锁过期秒数）
        // 返回 {"CACHED", value} 表示命中缓存
        // 返回 {"OWNER", ""} 表示抢锁成功，需要执行 producer
        // 返回 {"WAIT", ""} 表示未命中缓存也没抢到锁，需要轮询等待
        DefaultRedisScript<List> script = new DefaultRedisScript<>(
                """
                local cached = redis.call('get', KEYS[1])
                if cached then
                    return {'CACHED', cached}
                end
                local acquired = redis.call(
                    'set', KEYS[2], ARGV[1], 'NX', 'EX', ARGV[2]
                )
                if acquired then
                    return {'OWNER', ''}
                end
                return {'WAIT', ''}
                """,
                List.class
        );
        List<?> decision = redis.execute(
                script,
                List.of(valueKey, lockKey),
                owner,
                "30"
        );
        String decisionType = String.valueOf(decision.get(0));
        // 情况一：缓存命中，直接返回缓存中的结果。
        if ("CACHED".equals(decisionType)) {
            // 从这里开始是新增的：命中缓存 = 省了一次真实 AI 调用，记一笔 hit 账。
            cost.recordHit(key);
            // 新增结束
            return String.valueOf(decision.get(1));
        }
        // 情况二：抢到锁，执行 producer 并将结果写入缓存。
        if ("OWNER".equals(decisionType)) {
            try {
                stateMachine.transit(key, SingleflightStateMachine.State.RUNNING);
                String result = runProducer(key, ttl, producer);
                redis.opsForValue().set(
                        valueKey,
                        result,
                        ttl
                );
                return result;
            } finally {
                // 无论 producer 成功还是抛出异常，都必须释放锁。
                redis.delete(lockKey);
            }
        }

        // 情况三：未抢到锁时轮询等待结果，超时后自行调用，保持原有兜底逻辑。
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String result = redis.opsForValue().get(valueKey);
            if (result != null) {
                // 从这里开始是新增的：等锁方最终拿到的是别人写好的缓存，同样省了一次 AI 调用。
                cost.recordHit(key);
                // 新增结束
                return result;
            }
            // 每次轮询间隔 50 毫秒，避免高频空转消耗 CPU。
            // 这里把“线程休眠”的小操作直接内联进轮询循环，因为它只在本处使用一次，
            // 没必要单独拆出一个方法，内联后读代码时能顺着循环看到完整的等待节奏。
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // 线程被中断说明外部希望停止等待，需要恢复中断标志并立即抛异常，
                // 避免循环被异常打断后线程状态丢失，也避免把中断信号悄悄吞掉。
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "singleflight wait interrupted",
                        e
                );
            }
        }
        takeover.register(key, ttl, producer);
        throw new RetryableSingleflightException(
                "wait timeout, producer registered for timeout takeover",
                null
        );
    }
    private String executeLocal(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        long expiresAt = System.currentTimeMillis()
                + ttl.toMillis();
        localExpiry.putIfAbsent(key, expiresAt);
        // computeIfAbsent：只有 key 第一次出现时才创建新的 CompletableFuture，
        // 后续的并发请求会复用同一个 future，从而实现请求合并。
        CompletableFuture<String> future =
                localCache.computeIfAbsent(
                        key,
                        ignored -> CompletableFuture.supplyAsync(
                                producer
                        )
                );
        try {
            // join() 会阻塞等待第一个请求的 producer 执行完成。
            return future.join();
        } finally {
            localExpiry.putIfAbsent(key, expiresAt);
        }
    }
    private String runProducer(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        try {
            // 抢到锁的这一次真实执行生产者，成功结果由 Singleflight 分发给等待方。
            String value = producer.get();
            // 从这里开始是新增的：执行成功 → 终态 SUCCEEDED，等待方读到后直接回放结果缓存。
            stateMachine.transit(key, SingleflightStateMachine.State.SUCCEEDED);
            // 新增结束
            return value;
        } catch (RuntimeException error) {
            // 失败先分类，再决定是"直接抛错"还是"登记重试"。
            FailureClassifier.FailureType type =
                    failureClassifier.classify(error);
            // 从这里开始是新增的：无论哪类失败都先记下原因，等待方与排障都能读到。
            stateMachine.markReason(key, error.getClass().getSimpleName());
            // 新增结束
            if (type
                    == FailureClassifier.FailureType.NON_RETRYABLE) {
                // 从这里开始是新增的：不可重试 → 终态 FAILED，等待方读到后立刻失败，不再空等。
                stateMachine.transit(key, SingleflightStateMachine.State.FAILED);
                // 新增结束
                throw error;
            }
            // 从这里开始是新增的：可重试 → 先转到 TAKEN_OVER，再由接管服务在 TTL 过期后重试。
            stateMachine.transit(key, SingleflightStateMachine.State.TAKEN_OVER);
            // 新增结束
            takeover.register(
                    key,
                    ttl,
                    producer
            );
            throw new RetryableSingleflightException(
                    "producer registered for timeout takeover",
                    error
            );
        }
    }
    @Scheduled(fixedDelay = 600_000)
    public void clearExpiredLocalCache() {
        long now = System.currentTimeMillis();
        // 遍历过期时间表，找出所有已过期的 key。
        for (Map.Entry<String, Long> entry
                : localExpiry.entrySet()) {
            if (entry.getValue() <= now) {
                String key = entry.getKey();
                CompletableFuture<String> future =
                        localCache.get(key);
                // 只有当 future 已完成或不存在时，才安全地清理过期条目。
                if (future == null || future.isDone()) {
                    localExpiry.remove(key, entry.getValue());
                    localCache.remove(key, future);
                }
            }
        }
    }
    private boolean redisAvailable() {
        try {
            String pong = redis.execute(
        (RedisCallback<String>) connection -> connection.ping()
);
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ignored) {
            // 任何异常（网络超时、连接拒绝等）都视为 Redis 不可用。
            return false;
        }
    }
    public record FencedValue(
            // AI 生成的结果文本。
            String value,
            // 单调递增令牌，用于防止旧结果覆盖新结果。
            long token
    ) {
    }
}
