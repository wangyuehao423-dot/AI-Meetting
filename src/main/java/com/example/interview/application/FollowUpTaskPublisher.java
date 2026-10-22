// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
// 从这里开始是修改的：move 需要 RedisListCommands.Direction 枚举，补上该 import。
import org.springframework.data.redis.connection.RedisListCommands.Direction;
// 修改结束
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 追问任务发布/消费服务。
 *
 * 为什么用 Redis List 而不是 RabbitMQ/Kafka：项目已经引入了 Redis，追问任务的并发量也不高，
 * 不值得为一个队列再引入一套中间件；三 key 的结构换来的是"消费者崩溃不丢任务"。
 *
 * 为什么发布和消费放在同一个类里：两者操作同一个队列的三个 key，
 * 分开反而要多维护一份 key 常量的拷贝；将来真要分离，把消费逻辑抽出去即可。
 */
@Service
public class FollowUpTaskPublisher {

    // ---- Redis Key 定义 ---- //

    /** 主队列：待处理的追问任务，使用 Redis List 结构。 */
    private static final String QUEUE =
            "interview:task:follow-up";

    /** 处理中队列：已取出但尚未完成的追问任务。
     *  为什么必须有它：消息一旦 pop 出来就离开主队列，消费者这时崩溃任务就凭空消失；
     *  压在 processing 里，至少还能被人看到、被补偿任务捞回来。 */
    private static final String PROCESSING =
            "interview:task:follow-up:processing";

    /** 死信队列：超过最大重试次数的失败任务。
     *  死信不会被自动消费，留原文是为了人工/定时任务排查，而不是让失败静默消失。 */
    private static final String DEAD =
            "interview:task:follow-up:dead";

    /** 最大重试次数。到顶就进死信，避免一条永远失败的任务无限重投。 */
    private static final int MAX_RETRY = 3;

    // ---- 依赖注入 ---- //

    // 用 StringRedisTemplate 而不是 RedisTemplate<String, FollowUpTask>：
    // 我们用 Jackson 手工控制序列化格式，队列里存的永远是"人也能看懂"的 JSON 字符串，
    // 出问题时直接去 Redis 里看一眼原文就能定位，不依赖框架的序列化黑盒。
    private final StringRedisTemplate redis;
    // Jackson 序列化器：入队前把 FollowUpTask 转成 JSON，出队后再转回对象。
    private final ObjectMapper mapper;

    // 追问生成的核心业务逻辑，消费端只负责"取任务、调它、按结果收尾"。
    private final FollowUpGenerationService generation;

    // 构造器注入三个协作者：队列客户端、JSON 序列化器、追问生成服务。
    // 字段全是 final，消费在独立线程池里执行，依赖不会被中途替换。
    public FollowUpTaskPublisher(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            FollowUpGenerationService generation
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.generation = generation;
    }

    /**
     * 发布追问任务到 Redis 队列。
     *
     * 由 InterviewWorkflowService.judge 在评分落库、状态推进之后调用。
     * 为什么这个方法不是 @Async：rightPush 是毫秒级操作，异步化没有收益；
     * 更要紧的是发布失败必须让调用方当场感知（抛异常），异步化会把异常吞进另一个线程。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 当前题目 ID
     * @param score      当前题的评分，决定追问深度
     */
    public void publish(
            UUID sessionId,
            UUID questionId,
            double score
    ) {
        try {
            // 序列化成 JSON 推入主队列右侧。为什么 rightPush 配消费端的 leftPop：
            // 右进左出才是 FIFO，先提交的答案先被追问；两边用错就变成后进先出，题序会乱。
            redis.opsForList().rightPush(
                    QUEUE,
                    mapper.writeValueAsString(
                            new FollowUpTask(
                                    sessionId,
                                    questionId,
                                    score,
                                    0  // 初始重试次数为 0，每次重投才 +1
                            )
                    )
            );
        } catch (JsonProcessingException e) {
            // 序列化失败通常意味着 FollowUpTask 的字段类型不可序列化，是代码问题而非偶发故障，
            // 重试多少次都一样，所以直接抛不可恢复异常，让它暴露在日志里。
            throw new IllegalStateException(
                    "cannot serialize follow-up task",
                    e
            );
        }
    }

    /**
     * 尝试消费一个追问任务。
     *
     * 核心是 leftPopAndLeftPush 这条原子命令：从主队列头部取出，同时压进 processing 队列，
     * 两步要么都成功要么都失败——换成"先 pop 再 push"一旦中间挂掉，任务就没了。
     * 超时 200 毫秒：主队列为空时最多等 200ms 就返回 null，避免在这里无限阻塞、占着线程。
     *
     * 调用频率由外部定时任务/循环控制，@Async 只表示这条消费跑在自己的线程池里。
     */
    @Async("followUpTaskExecutor")
    public void consumeOnce() {
        // 一条原子命令搞定"出队 + 移位"：从主队列头部取出，同时压进 processing 队列。
        // 中途消费者崩溃时任务仍留在 processing 里，不会凭空消失。
        String raw = redis.opsForList()
                // 从这里开始是修改的：原代码调用的 leftPopAndLeftPush(QUEUE, PROCESSING, 200, TimeUnit.MILLISECONDS)
                // 在当前 Spring Data Redis 的 ListOperations 上并不存在（编译不过），
                // 改为与 JudgeTaskPublisher 一致的 Direction 版 move：从 QUEUE 左侧弹出并原子地压进
                // PROCESSING 左侧。仍然是"右进左出"的 FIFO 语义（publish 用的是 rightPush），
                // 且保持"出队+移位"的原子性，消费者崩溃时任务仍留在 processing 里不会丢。
                // 区别只是去掉了 200ms 阻塞等待——消费由外层定时循环反复触发，非阻塞即可。
                .move(
                        QUEUE,
                        Direction.LEFT,
                        PROCESSING,
                        Direction.LEFT
                );
        // 修改结束
        if (raw == null) {
            // 队列为空，本次没有可消费的任务，直接收工。
            return;
        }

        // 先声明成 null：反序列化可能失败，catch 里要靠它区分"任务坏掉了"和"处理任务时失败"。
        FollowUpTask task = null;
        try {
            // 反序列化 JSON 为 FollowUpTask 对象
            task = mapper.readValue(raw, FollowUpTask.class);

            // 调用核心业务逻辑生成追问：决策 → Singleflight 跑 LiteFlow 链 → 落库 → 推进状态。
            generation.generate(task);

            // 生成成功，把这条消息从 processing 里摘掉。
            // 为什么用 remove 而不是 leftPop：processing 里可能压着多条任务，
            // leftPop 会摘错别人的消息，remove 按"值 + 数量 1"精确删掉当前这条。
            redis.opsForList().remove(
                    PROCESSING,
                    1,     // 只删 1 个匹配元素
                    raw    // 匹配的内容就是这条任务的原始 JSON
            );
        } catch (Exception e) {
            // 消费失败（AI 超时、数据库异常等）：交给 retryOrDeadLetter 决定重投还是进死信。
            // 这里故意不把异常抛出去——抛出会让任务永远留在 processing 里没人管。
            retryOrDeadLetter(raw, task);
        }
    }

    /**
     * 处理消费失败的任务：重投主队列或进死信队列。
     *
     * @param raw  原始 JSON 字符串（重投/进死信都以它为准，保证内容不变形）
     * @param task 解析后的任务对象；反序列化失败时为 null
     */
    private void retryOrDeadLetter(
            String raw,
            FollowUpTask task
    ) {
        // 反序列化失败（task 为 null）或重试次数已达上限 → 直接进死信，不再重试。
        // 为什么不重试反序列化失败的任务：JSON 本身已经坏了，重投多少次都还是坏的那一条。
        if (task == null || task.retryCount() >= MAX_RETRY) {
            // 原文原样丢进死信队列，方便人工照原样排查。
            redis.opsForList().rightPush(DEAD, raw);
            // 同时从 processing 摘掉：不摘的话补偿任务会一直以为它还"卡在处理中"。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
            return;
        }

        try {
            // 重投：只把 retryCount 加一，其余字段原样带回，重新放回主队列尾部排队。
            FollowUpTask retry = new FollowUpTask(
                    task.sessionId(),
                    task.questionId(),
                    task.score(),
                    task.retryCount() + 1  // 重试次数 +1，到 MAX_RETRY 就改走死信
            );
            redis.opsForList().rightPush(
                    QUEUE,
                    mapper.writeValueAsString(retry)
            );
            // 新消息已经进主队列，这条旧的要从 processing 摘掉，否则会留下永不消失的幽灵消息。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
        } catch (JsonProcessingException e) {
            // 重投时序列化失败：异常只能抛出去，此时任务仍留在 processing 队列中，
            // 需要补偿机制来清理（与评分队列的补偿任务同一套思路）。
            throw new IllegalStateException(
                    "cannot serialize follow-up retry",
                    e
            );
        }
    }
}
// 新增结束
