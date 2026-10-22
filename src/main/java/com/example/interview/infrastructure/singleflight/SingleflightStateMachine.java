// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

// ============================================================
// Single-flight 请求生命周期状态机
// ============================================================
// 职责：把"同一个 key 的这次 AI 调用现在走到哪一步"显式建模出来，
// 落库到 Redis Hash，供跨节点的其他请求查询与决策。
//
// 为什么 Single-flight 需要一个状态机：
//   只有"锁 + 结果缓存"两个设施时，等待方无法区分下面三种情况，
//   只能靠轮询猜测：
//     1. 执行方正在跑（应该继续等）；
//     2. 执行方跑挂了且失败不可重试（应该立刻报错，不要让用户白等）；
//     3. 执行方跑挂了但失败可重试（应该交给接管服务，而不是所有人一起重试）。
//   把生命周期显式建模后，等待方读一次状态就能做出正确决策，
//   同时每一次转移都留下痕迹，排障时能直接看到"卡在哪一步"。
//
// 与 FencingToken 的分工：
//   FencingToken 解决"旧结果不许覆盖新结果"；
//   本状态机解决"当前这次请求处于哪个阶段"。
//   前者管数据新旧，后者管流程阶段，两者互补。
// ============================================================
@Component
public class SingleflightStateMachine {

    // 状态在 Redis 中的 key 前缀，实际 key 为 sfs:state:{业务key}。
    private static final String STATE_PREFIX = "sfs:state:";
    // 状态记录的存活时间：略长于生产者 TTL，保证接管重试期间状态仍可查。
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    // Hash 中的字段名。
    private static final String FIELD_STATE = "state";
    private static final String FIELD_FENCE = "fence";
    private static final String FIELD_REASON = "reason";

    /**
     * 请求生命周期状态。
     *
     * <p>为什么把终态单独标注出来：终态不允许再被转移到 RUNNING，
     * 防止"已经失败的任务被另一个节点重新拉起来执行两遍"。
     */
    public enum State {
        // 等待中：请求已登记，但还没人抢到执行权。
        PENDING(false),
        // 执行中：某个节点已抢到锁，正在跑 producer。
        RUNNING(false),
        // 成功：结果已写入结果缓存，后来者可直接回放（终态）。
        SUCCEEDED(true),
        // 不可重试失败，终态，不再重试。
        FAILED(true),
        // 可重试失败：已交给接管服务，等 TTL 过期后重试。
        TAKEN_OVER(true);

        // 是否为终态：终态不允许再被转移回 RUNNING。
        private final boolean terminal;

        State(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean terminal() {
            return terminal;
        }
    }

    /**
     * 合法转移表。
     *
     * <p>为什么用 EnumMap 静态表而不是一串 if：转移规则是这个类的核心契约，
     * 用"状态 → 允许的下一批状态"表达出来，新增状态时只改这张表，
     * 校验逻辑一行都不用动；同时也天然拒绝了自环和回退。
     */
    private static final Map<State, Set<State>> TRANSITIONS =
            new EnumMap<>(State.class);

    static {
        // PENDING 只能被推进成 RUNNING，或直接被判定为 FAILED。
        TRANSITIONS.put(State.PENDING, Set.of(State.RUNNING, State.FAILED));
        // RUNNING 有三种出口：执行成功、不可重试失败、可重试（转交接管）。
        TRANSITIONS.put(State.RUNNING, Set.of(
                State.SUCCEEDED,
                State.FAILED,
                State.TAKEN_OVER
        ));
        // 下面两个写成空集合：SUCCEEDED / FAILED 是终态，不允许再被推进。
        TRANSITIONS.put(State.SUCCEEDED, Set.of());
        TRANSITIONS.put(State.FAILED, Set.of());
        // TAKEN_OVER 是被接管的中转态，接管重试后仍可能成功或失败。
        TRANSITIONS.put(State.TAKEN_OVER, Set.of(State.SUCCEEDED, State.FAILED));
    }

    // Redis 操作模板：状态存在 Redis Hash 里，多个实例都能读到同一份。
    private final StringRedisTemplate redis;

    // 构造器注入 Redis 模板。
    public SingleflightStateMachine(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登记一次请求：不存在的状态置为 PENDING，已存在则保持不变。
     *
     * @param key 业务 key（与 Singleflight 的 key 一致）
     * @return 登记后的当前状态
     */
    public State register(String key) {
        // 拼出状态在 Redis 里的 key：sfs:state:{业务key}。
        String redisKey = STATE_PREFIX + key;
        // putIfAbsent：只有还不存在时才写 PENDING，已存在就保持原状态（重复登记无副作用）。
        redis.opsForHash().putIfAbsent(redisKey, FIELD_STATE, State.PENDING.name());
        // 续期 TTL，避免某个 key 一直没人推进却永久占着内存。
        redis.expire(redisKey, STATE_TTL);
        // 返回登记后的真实状态（可能刚被另一个节点推进过）。
        return current(key);
    }

    /**
     * 尝试把状态从当前值推进到目标值。
     *
     * <p>为什么返回 boolean 而不是抛异常：状态转移失败在 Single-flight 里是
     * "正常竞争结果"（例如另一个节点已经写成功），调用方据此走回放分支即可，
     * 用异常表达会污染控制流。
     *
     * @param key    业务 key
     * @param target 目标状态
     * @return 转移成功返回 true；当前状态不存在、已是终态或不是合法后继返回 false
     */
    public boolean transit(String key, State target) {
        // 先读当前状态；从没登记过（null）就谈不上转移。
        State from = current(key);
        if (from == null) {
            return false;
        }
        // 查转移表：目标必须是当前状态的合法后继，否则拒绝（挡掉回退和自环）。
        Set<State> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            return false;
        }
        // 合法则写入新状态，并续期 TTL。
        String redisKey = STATE_PREFIX + key;
        redis.opsForHash().put(redisKey, FIELD_STATE, target.name());
        redis.expire(redisKey, STATE_TTL);
        return true;
    }

    /**
     * 读取当前状态。
     *
     * @param key 业务 key
     * @return 状态；从未登记过或已过期返回 null
     */
    public State current(String key) {
        // 从 Redis Hash 里取 state 字段。
        Object raw = redis.opsForHash().get(STATE_PREFIX + key, FIELD_STATE);
        // 没登记过、或者 key 已过期 → 返回 null，调用方按"无状态"处理。
        if (raw == null) {
            return null;
        }
        // 存进去的是枚举名字符串，这里再还原回枚举对象。
        return State.valueOf(raw.toString());
    }

    /**
     * 记录这次请求绑定的围栏令牌，便于排障时对照"结果属于哪一次执行"。
     *
     * @param key   业务 key
     * @param fence 由 FencingToken 递增得到的令牌
     */
    public void bindFence(String key, long fence) {
        // 把本次执行的围栏令牌写进同一个 Hash，排障时能对照"结果属于哪一次执行"。
        redis.opsForHash().put(STATE_PREFIX + key, FIELD_FENCE, Long.toString(fence));
    }

    /**
     * 记录失败原因，供等待方与排障使用。
     *
     * @param key    业务 key
     * @param reason 失败原因摘要（不要放入完整堆栈，避免占用 Redis）
     */
    public void markReason(String key, String reason) {
        // 只存失败原因摘要（类名或简短说明），不放完整堆栈，避免撑大 Redis value。
        redis.opsForHash().put(STATE_PREFIX + key, FIELD_REASON, reason);
    }
}
// 新增结束
