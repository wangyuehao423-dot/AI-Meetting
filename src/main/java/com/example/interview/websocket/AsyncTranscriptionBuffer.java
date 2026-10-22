package com.example.interview.websocket;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncTranscriptionBuffer {

    // ============================================================
    // 音频分片记录 —— receive 的入队单元
    // ============================================================
    // 字段与前端上报的 JSON 一一对应：
    //   segId —— 分片序号（seg_id），用于去重和排序
    //   text  —— 分片文本内容
    //   pgs   —— 分片状态，如 "speaking" / "silence"
    //   range —— 音频范围起始位置（rg 数组的第一个元素）
    //   begin —— 分片开始时间位置（bg 数组的第一个元素）
    //   end   —— 是否最终确认结果（ed）
    // ============================================================
    public record AudioSegment(
            int segId,
            String text,
            String pgs,
            int range,
            int begin,
            boolean end
    ) {
    }

    /**
     * 按 sessionId 隔离的音频分片队列。
     *
     * <p>为什么 key 用 sessionId：一场面试对应一个转写上下文，不同面试的
     * 音频分片绝不能混入同一个队列，否则 A 用户的声音片段会被并进 B 用户的
     * 转写文本。Map 的 key 是 sessionId，value 是容量 256 的有界队列，
     * 队列满时 receive 会阻塞等待最多 200ms，起到天然的背压作用。
     */
    private final Map<String, ArrayBlockingQueue<AudioSegment>>
            queues = new ConcurrentHashMap<>();

    /**
     * 已关闭会话的标记集合，用于终止该会话的消费循环。
     *
     * <p>为什么需要它：消费线程阻塞在队列上等待分片，仅把队列从 Map 中摘除
     * 并不能让它退出。如果不加标记，每个结束的面试都会永久占用一个转写线程，
     * 连接数一多就把 transcriptionTaskExecutor 占满，后续会话的转写再也拿不到
     * 线程。close 写入标记、消费循环每轮检查标记，两者配合完成线程回收。
     */
    private final Set<String> closedSessions =
            ConcurrentHashMap.newKeySet();

    /**
     * 接收一个音频分片并放入该会话对应的队列。
     *
     * <p>为什么用带超时的 offer 而不是 put：WebSocket 接收线程不能无限阻塞，
     * 否则该连接的接收链路会被拖死；offer 等待最多 200ms，队列仍满就返回
     * false，由调用方决定丢弃或断开连接，保证接收线程始终能继续处理新分片。
     *
     * @param sessionId 当前 WebSocket 连接对应的面试会话 ID
     * @param segment   一个音频转写分片（含片段序号和文本内容）
     * @return 入队成功返回 true；队列已满且超时仍无空位返回 false
     */
    public boolean receive(
            String sessionId,
            AudioSegment segment
    ) {
        ArrayBlockingQueue<AudioSegment> queue =
                queues.computeIfAbsent(
                        sessionId,
                        ignored -> new ArrayBlockingQueue<>(256)
                );
        try {
            return queue.offer(
                    segment,
                    200,
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            // 接收线程被中断（如服务关闭）时恢复中断标记并视为入队失败，
            // 调用方（WebSocket 处理器）据此丢弃当前分片，不阻塞接收链路。
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 异步消费指定会话的音频分片并合并进转写上下文。
     *
     * <p>方法由 @Async("transcriptionTaskExecutor") 注解在独立的转写线程池
     * 中执行，与接收线程解耦：接收只管入队，本方法在后台用阻塞 take 等待
     * 新分片。为什么用无限循环 + take：take 在没有分片时会挂起线程而不占用
     * CPU，直到有新分片才被唤醒，配合 mergeSegment 把分片按序号合并成完整
     * 转写文本。线程被中断（如面试结束清理连接）时恢复中断标记并退出循环。
     *
     * @param sessionId 要消费的面试会话 ID，也是队列的 key
     * @param context   该会话的转写上下文，合并后的文本写入其中
     */
    @Async("transcriptionTaskExecutor")
    public void consume(
            String sessionId,
            TranscriptionSessionContext context
    ) {
        // 连接（重新）建立时把会话标记回"活跃"，
        // 避免同一 sessionId 重连后仍被上一轮的关闭标记立刻终止。
        closedSessions.remove(sessionId);
        ArrayBlockingQueue<AudioSegment> queue =
                queues.computeIfAbsent(
                        sessionId,
                        ignored -> new ArrayBlockingQueue<>(256)
                );
        for (;;) {
            // 会话已关闭则退出循环，把线程还给线程池。
            if (closedSessions.contains(sessionId)) {
                return;
            }
            try {
                // 用带超时的 poll 而不是无限阻塞的 take：
                // 每轮最多等 200ms 就回头检查一次关闭标记，
                // 保证会话结束后消费线程能在一个超时周期内退出。
                AudioSegment segment = queue.poll(
                        200,
                        TimeUnit.MILLISECONDS
                );
                if (segment == null) {
                    continue;
                }
                context.mergeSegment(
                        segment.segId(),
                        segment.text(),
                        segment.pgs(),
                        segment.range(),
                        segment.begin(),
                        segment.end()
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 面试结束时清理该会话的队列，释放内存。
     *
     * <p>为什么必须清理：如果不 remove，每个结束的面试都会在 Map 中遗留一个
     * 队列对象，长期运行下来形成内存泄漏。只摘除队列还不够——消费线程手里
     * 持有的是队列的局部引用，摘除 Map 不会让它停下来，所以这里先写入关闭
     * 标记再摘除队列：消费循环每轮 poll 超时后会检查该标记并自行 return，
     * 队列与线程因此都能被回收，无需调用方再额外中断线程。
     *
     * @param sessionId 要清理的面试会话 ID
     */
    public void close(String sessionId) {
        closedSessions.add(sessionId);
        queues.remove(sessionId);
    }
}