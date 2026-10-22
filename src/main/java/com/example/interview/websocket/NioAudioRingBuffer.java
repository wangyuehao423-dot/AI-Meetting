package com.example.interview.websocket;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

// ============================================================
// NioAudioRingBuffer —— 基于 java.nio.ByteBuffer 的有界环形异步缓冲
// ============================================================
// 这是"亮点4"里把措辞落成真实现的关键一块：之前音频的"缓冲解耦"是用
// ArrayBlockingQueue 装文本分片；而 NLS 的音频转发（pushAudio）只是"收一帧、
// 当场 sendBinary 一次"，接收线程直接被下游发送阻塞住了，根本没有异步缓冲。
//
// 本类用真正的 java.nio.ByteBuffer 数组搭一个有界环形槽位：
//   · 生产端（WebSocket 接收线程）offer()——队列满时立刻返回 false，是天然背压；
//   · 消费端（每个会话一个专用线程）take()——拿不到帧时挂起等待，不忙转；
//   · 音频接收线程因此与 NLS 推流线程彻底解耦，接收不再被下游网络阻塞。
// 这既让"借鉴 NIO Buffer 的异步缓冲思想"字面成立（缓冲本身真的是 NIO ByteBuffer），
// 也带来真实的健壮性收益：NLS 网关卡住时，接收线程不会一起卡死整条录音流。
// ============================================================
final class NioAudioRingBuffer {

    // 环形槽位数组，每个槽放一个"已拼好 NLS 帧头的 PCM 帧"（java.nio.ByteBuffer）。
    private final ByteBuffer[] slots;
    private final Object lock = new Object();
    // head 指向下一个要消费的位置，tail 指向下一个要写入的位置，count 是当前占用数。
    private int head;
    private int tail;
    private int count;
    // 关闭标记：生产端 stop 时置位，消费端据此"排空余量后退出"。
    private boolean closed;

    NioAudioRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "ring capacity must be > 0"
            );
        }
        this.slots = new ByteBuffer[capacity];
    }

    /**
     * 生产端：放入一帧音频，返回是否入队成功。
     *
     * <p>为什么用非阻塞式返回、而不是阻塞等待空位：放在这里的生产者是 WebSocket
     * 接收线程，它绝不能因为下游 NLS 推流慢而无限等下去，否则整条录音连接会被拖死。
     * 队列已满直接返回 false，由调用方（pushAudio）丢弃这一小片——音频是流式的，
     * 丢一小片会被后续识别分片自然补齐，而线程卡死是不可恢复的恶性故障。
     *
     * @param frame 已拼好 NLS 4 字节帧头的 PCM 帧
     * @return true=已入队；false=队列满或已关闭（调用方应丢弃）
     */
    boolean offer(ByteBuffer frame) {
        synchronized (lock) {
            if (count == slots.length || closed) {
                return false;
            }
            slots[tail] = frame;
            tail = (tail + 1) % slots.length;
            count++;
            // 唤醒可能在 take 上等待的消费线程，让它立刻来取这一帧。
            lock.notifyAll();
            return true;
        }
    }

    /**
     * 消费端：取走一帧，最多等待 timeoutMs。
     *
     * <p>为什么用带超时的等待而不是无限 take：消费线程需要周期性地看一眼"是否已关闭"
     * 来决定退出；纯无限阻塞（像队列 take 那样始终等到天荒地老）在关闭后就再也无法
     * 被唤醒退出，会永久占住线程。带超时等待 + 每轮检查 closed 标记，才能干净回收线程。
     *
     * @param timeoutMs 单次等待上限（毫秒）
     * @return 一帧音频；超时或已关闭且无剩余帧时返回 null
     */
    ByteBuffer take(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (lock) {
            long remain;
            // 只要：还没到超时、且队列是空的、且还没关闭，就继续等。
            while ((remain = deadline - System.nanoTime()) > 0
                    && count == 0
                    && !closed) {
                TimeUnit.NANOSECONDS.timedWait(lock, remain);
            }
            if (count == 0) {
                // 触发条件：要么超时、要么已关闭——两种情况都没有帧可取。
                return null;
            }
            ByteBuffer frame = slots[head];
            slots[head] = null;   // 置空便于 GC，避免槽位长时间引用旧帧。
            head = (head + 1) % slots.length;
            count--;
            lock.notifyAll();
            return frame;
        }
    }

    /**
     * 是否仍为空（供消费端判断"排空与否"）。
     */
    boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    /**
     * 是否已关闭（供消费端判断是否该退出）。
     */
    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /**
     * 关闭缓冲：唤醒正在等待的消费线程，让它周期性地发现关闭标记并退出。
     */
    void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }
}