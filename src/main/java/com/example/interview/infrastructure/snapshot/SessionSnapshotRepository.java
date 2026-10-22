package com.example.interview.infrastructure.snapshot;

import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

// 从这里开始是新增的：@Qualifier 所在包，用于按名字指定要注入哪一个 Executor。
import org.springframework.beans.factory.annotation.Qualifier;
// 新增结束
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Repository
public class SessionSnapshotRepository {
    // **Redis 热缓存前缀，实际 key 为 snapshot:{sessionId}。**
    private static final String CACHE_PREFIX = "snapshot:";
    // **热数据保留 30 分钟，过期后由下一次访问懒加载。**
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final SnapshotMongoRepository mongo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Executor cacheWriter;

    // 从这里开始是新增的：能发"带条件原子更新"命令的依赖。为什么非要它：
    // Spring Data 的 MongoRepository 接口只暴露 CRUD，给不出 findAndModify 这种
    // 把"过滤"和"更新"合成一条命令的能力，而原子性正是靠这条命令拿到的。
    private final MongoTemplate mongoTemplate;

    // Redis 读写失败只告警、不上抛，所以必须先有这个 log 对象，
    // 写法照项目里 InterviewPreparationAsyncService 的 LoggerFactory 用法。
    private static final Logger log =
            LoggerFactory.getLogger(SessionSnapshotRepository.class);
    // 新增结束

    // 以下构造器照旧，只在参数末尾追加一个 MongoTemplate mongoTemplate，
    // 并在构造体末尾补一行 this.mongoTemplate = mongoTemplate;
    public SessionSnapshotRepository(
            SnapshotMongoRepository mongo,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            // 从这里开始是新增的：给 cacheWriter 指定 Bean 名字。
            // 为什么必须指明名字：AsyncConfig 里注册了 9 个 Executor（面试准备、出题、简历解析、
            // AI 评分、追问、报告生成、神态分析、语音转写、缓存回填），再加上 SchedulingConfig
            // 里的 taskScheduler，容器里同类型的 Bean 一共 10 个。
            // 只写类型（Executor cacheWriter）会让 Spring 报
            // "expected single matching bean but found 10" 直接启动失败。
            // 这里选 cacheWriteTaskExecutor，因为它的 Bean 定义注释写明是
            // "缓存回填等轻量异步操作，不依赖 @Async 注解，供 CompletableFuture 使用"，
            // 与下面 load() 里 CompletableFuture.runAsync(..., cacheWriter) 的回填场景完全对应。
            @Qualifier("cacheWriteTaskExecutor") Executor cacheWriter,
            // 新增结束
            MongoTemplate mongoTemplate
    ) {
        this.mongo = mongo;
        this.redis = redis;
        this.mapper = mapper;
        this.cacheWriter = cacheWriter;
        this.mongoTemplate = mongoTemplate;
    }

    // 从这里开始是新增的：load / evictCache / writeCache 三个方法整段重写，
    // 把 Redis 从"必选项"降级成"可选加速层"。原实现是三个方法各自裸调 Redis，
    // 任何 Redis 异常都会直接抛到接口层。
    /**
     * 读取会话快照：Redis 热缓存优先，未命中回源 Mongo 并异步回填。
     *
     * <p>为什么 Redis 异常必须被吞掉而不是上抛：Redis 在本项目里只是
     * "加速读取的热缓存"，真正的数据源是 MongoDB。缓存不可用时最差的结果是
     * "慢一点"，而不是"面试做不下去"。所以这里把所有 Redis 异常统一降级为
     * "缓存未命中"，继续走 Mongo 冷路径，保证 Redis 故障期间会话仍可推进。
     *
     * @param sessionId 面试会话 ID
     * @return 该会话的快照；Mongo 也没有时返回新创建的 INIT 快照
     */
    public InterviewSessionSnapshot load(UUID sessionId) {
        // 拼出 Redis 热缓存的 key。
        String key = cacheKey(sessionId);

        // 第一步：尝试读热缓存。命中就直接返回，省掉一次 Mongo 查询。
        // 任何 Redis 异常都降级为"未命中"，继续往下走冷路径。
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return fromJson(cached);   // 反序列化缓存里的 JSON 快照
            }
        } catch (RuntimeException error) {
            // 只告警、不抛出：Redis 挂了最多是"慢一点"，不能让面试做不下去。
            log.warn(
                    "redis read failed, fall back to mongo. sessionId={}",
                    sessionId,
                    error
            );
        }

        // 第二步：冷路径读 Mongo。这是真正的数据源，Redis 挂了也必须走这里。
        SnapshotDocument document = mongo.findBySessionId(sessionId.toString()).orElse(null);
        if (document != null) {
            InterviewSessionSnapshot snapshot = toDomain(document);
            // **MongoDB 命中后异步回填 Redis，不阻塞当前读取请求。**
            CompletableFuture.runAsync(
                    () -> writeCache(key, snapshot),
                    cacheWriter                        // 使用自定义线程池，不走 ForkJoinPool
            );
            return snapshot;
        }

        // 第三步：Mongo 也没有时，创建 INIT 快照并同步写入 MongoDB（数据源必须写成功），
        // 再写一次热缓存——写缓存失败只告警，不影响本次返回。
        SnapshotDocument initial = createInitialDocument(sessionId);
        SnapshotDocument saved = mongo.save(initial);
        InterviewSessionSnapshot snapshot = toDomain(saved);
        writeCache(key, snapshot);
        return snapshot;
    }

    /**
     * 淘汰热缓存。
     *
     * <p>为什么淘汰失败不算错：淘汰只是让下一次读回源 Mongo。
     * 如果因为 Redis 异常把 CAS 流程打断，就变成"缓存故障引发数据写不进去"，
     * 主次颠倒。
     */
    private void evictCache(UUID sessionId) {
        try {
            // 淘汰热缓存：删掉之后，下一次 load 会从 Mongo 重新加载最新版本。
            redis.delete(cacheKey(sessionId));
        } catch (RuntimeException error) {
            // 淘汰失败不算错：缓存到期会自动失效，不能因此把 CAS 流程打断。
            log.warn(
                    "redis evict failed, cache will expire by ttl. sessionId={}",
                    sessionId,
                    error
            );
        }
    }

    /**
     * 写入热缓存。
     *
     * <p>为什么写失败只告警：缓存写失败会让下一次读多走一次 Mongo，
     * 属于性能问题而非正确性问题。序列化失败同理——只要 Mongo 里数据是好的，
     * 会话状态就不会丢。
     */
    private void writeCache(String key, InterviewSessionSnapshot snapshot) {
        try {
            // 把快照序列化成 JSON 写进 Redis，并带上 TTL（本项目是 30 分钟）。
            redis.opsForValue().set(
                    key,
                    mapper.writeValueAsString(snapshot),
                    CACHE_TTL
            );
        } catch (JsonProcessingException error) {
            // 序列化失败：只要 Mongo 里数据是好的，会话状态就不会丢，只告警。
            log.warn("cannot serialize session snapshot. key={}", key, error);
        } catch (RuntimeException error) {
            // Redis 写失败：缓存只是加速手段，跳过即可。
            log.warn("redis write failed, skip cache. key={}", key, error);
        }
    }
    // 新增结束（load / evictCache / writeCache 三个方法都在新增范围内）

    // 以下是原有代码，不用动：这两个方法都不碰 Redis，所以不需要降级。
    private String cacheKey(UUID sessionId) {
        return CACHE_PREFIX + sessionId;
    }
    // **将 Redis JSON 反序列化为领域快照。**
    private InterviewSessionSnapshot fromJson(String json) {
        try {
            return mapper.readValue(
                    json,
                    InterviewSessionSnapshot.class
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "cannot deserialize session snapshot",
                    e
            );
        }
    }

    // 从这里开始是新增的：替换原来"读版本 → 比对 → delete → save"那一串实现。
    /**
     * 期望版本 + 1 的原子写回。
     *
     * <p>为什么不能继续用"先查版本、再 delete + save"：那是三段独立的网络调用，
     * 两个并发请求会同时读到同一版本、同时通过校验，最终互相覆盖且都返回成功，
     * 乐观锁就退化成了"没有锁"。而提交答案、结束面试、评分工作流、追问生成、
     * 面试准备这五处调用方，全都指望它只放行一个。
     *
     * <p>这里改用 findAndModify 一条命令完成"过滤 + 更新"：
     * 过滤条件带上 sessionId 和期望的 version，MongoDB 保证单文档更新的原子性，
     * 只有当版本仍等于期望值时才会命中并返回更新后的文档；
     * 别人先改过就会返回 null，调用方据此判定 CAS 失败。
     *
     * @param oldValue 调用方读到的旧快照，其中的 version 是期望版本
     * @param newValue 状态机推进后的新快照
     * @return 更新成功返回 true；版本已被其他请求推进返回 false
     */
    public boolean compareAndSet(
            InterviewSessionSnapshot oldValue,
            InterviewSessionSnapshot newValue
    ) {
        // 期望版本来自调用方读到的旧快照，会被写进过滤条件里。
        UUID sessionId = oldValue.sessionId();

        // 过滤条件：sessionId 匹配，并且 version 仍等于期望版本。
        // 别人先推进过 version，这条过滤就会落空，findAndModify 直接返回 null。
        Query query = Query.query(
                Criteria.where("sessionId").is(sessionId.toString())
                        .and("version").is(oldValue.version())
        );

        // 要写入的新值：新状态、新版本号、累积的幂等请求 ID 集合。
        Update update = new Update()
                .set("state", newValue.state().name())
                .set("version", newValue.version())
                .set("processedRequestIds", new HashSet<>(newValue.processedRequestIds()));

        // findAndModify 把"过滤 + 更新"合成一条命令，由 MongoDB 保证单文档原子性；
        // returnNew(true) 表示返回更新后的文档（而不是更新前的）。
        SnapshotDocument saved = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SnapshotDocument.class
        );

        // 返回 null = 过滤没命中（版本已被别人推进，或文档不存在）→ CAS 失败。
        if (saved == null) {
            // 版本不匹配（别人先推进了）或文档不存在，两种情况都属于 CAS 失败。
            return false;
        }

        // 写回成功后淘汰热缓存，下一次 load 会从 Mongo 重新懒加载最新版本，
        // 避免缓存里留着旧 state，导致后续判断基于过期数据。
        evictCache(sessionId);
        return true;
    }
    // 新增结束

    // 以下是原有代码，不用动：领域对象与文档的互相转换、INIT 文档的构造。
    private InterviewSessionSnapshot toDomain(
            SnapshotDocument document
    ) {
        return new InterviewSessionSnapshot(
                UUID.fromString(document.getSessionId()),
                InterviewState.valueOf(document.getState()),
                document.getVersion(),
                document.getProcessedRequestIds()
        );
    }
    private SnapshotDocument createInitialDocument(
            UUID sessionId
    ) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(sessionId.toString());
        document.setSessionId(sessionId.toString());
        document.setState(InterviewState.INIT.name());
        document.setVersion(0);
        document.setProcessedRequestIds(new HashSet<>());
        return document;
    }

    private SnapshotDocument toDocument(
            InterviewSessionSnapshot snapshot
    ) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(snapshot.sessionId().toString());
        document.setSessionId(snapshot.sessionId().toString());
        document.setState(snapshot.state().name());
        document.setVersion(snapshot.version());
        document.setProcessedRequestIds(
                new HashSet<>(snapshot.processedRequestIds())
        );
        return document;
    }
}
