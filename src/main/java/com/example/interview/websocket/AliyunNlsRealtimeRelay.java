package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// ============================================================
// 阿里云 NLS 实时转写【后端中继】 —— 音频从"前端"推到"阿里云 NLS"
// ============================================================
// 为什么需要这个类（这正是"亮点4"里缺失的一块）：
//   SpeechTokenController 只负责签发 Token，AliyunNlsTokenService 只负责向阿里云换 Token，
//   真正的"音频→NLS→文本"链路此前是前端直连 NLS 完成的，后端只收文本分片、从不碰音频。
//   而亮点写的是"基于 WebSocket + 阿里云 NLS 的实时 ASR 链路"，按这个描述，后端应当
//   接收前端送来的音频并把流推到 NLS。本类就是补上这一环。
//
// 它做什么：
//   1. start(sessionId, previewSink) —— 为一场面试建立一条"上行 NLS WebSocket"，
//     并立即发 StartTranscription 指令（对话框协议与前端 nlsTranscriptionClient 完全一致，
//     只把 token/appkey 换成本地签发的值）。
//   2. pushAudio(sessionId, audio)  —— 把前端送来的 16k 单声道 PCM 原始字节，前面拼上
//     NLS 要求的 4 字节帧头（首字节 0x02 表示"音频数据帧"）后原样转发给 NLS。
//   3. NLS 每回一帧 TranscriptionResultChanged（一个识别分片），本类把它转成
//     AudioSegment 丢进 AsyncTranscriptionBuffer —— 复用现成的入队/去重/有序合并管线，
//     因此"接续去重"这一承诺是靠原链路兑现的，不另起炉灶。
//   4. stop(sessionId) —— 发送 0x04 帧结束上行流并关闭连接。
//
// 生命周期：一场面试一个 NLS 上行连接，key 用 sessionId；连接随前端"开始/停止录音"
// （audio_start / audio_end）建立与关闭，与 WebSocketHandler 的关闭回调联动清理。
// ============================================================
@Component
public class AliyunNlsRealtimeRelay {

    private static final Logger log =
            LoggerFactory.getLogger(AliyunNlsRealtimeRelay.class);

    // NLS 实时识别接入点（上海地域），必须 wss 且只支持 16k / 8k 采样率。
    private static final URI GATEWAY =
            URI.create("wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1");
    // 全项目统一用 16k 采样率，与前端降采样后的 PCM 一致。
    private static final int SAMPLE_RATE = 16000;
    // NLS 音频帧头（4 字节）：首字节 0x02=音频数据帧，其余字节 0（与前端 nlsAudioFrame 相同）。
    private static final byte[] AUDIO_HEADER = {0x02, 0, 0, 0};
    // NLS 结束帧头（4 字节）：首字节 0x04=音频已发完，请求停止，payload 为空。
    private static final byte[] END_HEADER = {0x04, 0, 0, 0};
    // 上行连接建立 / 收尾的等待上限，防止 NLS 网关不可达时把接收线程无限挂住。
    private static final Duration CONN_TIMEOUT = Duration.ofSeconds(5);

    private final AliyunNlsTokenService tokenService;
    private final AsyncTranscriptionBuffer buffer;
    private final ObjectMapper mapper;
    // Java 21 自带的 HTTP 客户端：Java 11+ 内置 java.net.http 模块，无需额外依赖。
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONN_TIMEOUT)
            .build();

    // sessionId -> 该面试的上行 NLS 连接。ConcurrentHashMap：一场面试可能被
    // 重连/多标签页并发触发 start，用 computeIfAbsent/get 保证不会建出两条上行。
    private final Map<String, UpstreamConnection> sessions =
            new ConcurrentHashMap<>();

    public AliyunNlsRealtimeRelay(
            AliyunNlsTokenService tokenService,
            AsyncTranscriptionBuffer buffer,
            ObjectMapper mapper
    ) {
        this.tokenService = tokenService;
        this.buffer = buffer;
        this.mapper = mapper;
    }

    /**
     * 为一场面试建立上行 NLS 连接并发送 StartTranscription 指令。
     *
     * <p>幂等：同一 sessionId 已存在连接时直接返回，避免重复建连浪费资源。
     * 为什么把"发指令"放这里而不是 pushAudio：StartTranscription 必须在第一条音频
     * 之前发出，前端发 audio_start 时就准备好上行，后续音频只管转发。
     *
     * <p>连接失败（密钥错误 / 网关不可达）会被吞掉并记日志——录音转写在业务上只是
     * "锦上添花"，失败了用户仍可退回文本输入，绝不能因为 NLS 挂了把整场面试打断。
     *
     * @param sessionId   面试会话 ID，作为上行连接的 key
     * @param previewSink 收到 NLS 临时分片后回调，把预览文本推回前端（null 表示不要预览）
     */
    public void start(String sessionId, Consumer<String> previewSink) {
        if (sessions.containsKey(sessionId)) {
            return;
        }
        try {
            UpstreamConnection conn = open(sessionId, previewSink);
            // 从这里开始是修改的：把"putIfAbsent + 若被抢先则复用"拿到前面判断。
            // 为什么要处理 prior：start 可能被并发触发（重连 / 多标签页），putIfAbsent
            // 只能保证 Map 里只有一条，但 putIfAbsent 返回的 prior 提示"这次新建是多余的"。
            // 多余的那条连接不能漏管，要立即 close，否则白占一个 NLS 上行会话配额。
            UpstreamConnection prior = sessions.putIfAbsent(sessionId, conn);
            if (prior != null) {
                closeWs(conn);
                return;
            }
            // 修改结束
            sendStart(conn);
            // 从这里开始是新增的：给这条上行启动"专属消费线程"。
            // 消费线程把 NioAudioRingBuffer 里的帧逐帧发给 NLS；StartTranscription 已同步
            // 发出、后续音频帧都在 ring 缓冲里排队，所以音频"接收线程"与"NLS 推流线程"
            // 从此彻底解耦（接收线程 offer 后立即返回，不再被 sendBinary 阻塞）。
            startConsumer(conn);
            // 新增结束
        } catch (Exception error) {
            log.error("open nls upstream failed, sessionId={}", sessionId, error);
        }
    }

    /**
     * 把前端送来的 PCM 原始字节推给 NLS。
     *
     * <p>前端通过业务 WebSocket 以二进制帧送来的是"裸 PCM"（不含 NLS 帧头），
     * 这里在头部拼上 4 字节帧头后转发——帧头是 NLS 认识音频的标记，漏掉会被网关
     * 判定为非法帧。若该会话还没有上行连接（忘了发 audio_start），静默丢弃：
     * 音频是流式的，丢一小片不影响整体转写。
     *
     * @param sessionId 面试会话 ID
     * @param audio     16k 单声道 int16 PCM 原始字节
     */
    public void pushAudio(String sessionId, byte[] audio) {
        UpstreamConnection conn = sessions.get(sessionId);
        if (conn == null || audio == null || audio.length == 0) {
            return;
        }
        // 4 字节帧头 + PCM 体，装进一个 ByteBuffer（这个帧就是 NIO 环形缓冲的存储单元）。
        ByteBuffer frame = ByteBuffer.allocate(AUDIO_HEADER.length + audio.length);
        frame.put(AUDIO_HEADER);
        frame.put(audio);
        frame.flip();
        // 从这里开始是修改的：不再当场 sendBinary，而是把帧交给该会话的 NIO 环形缓冲。
        // offer 非阻塞返回：队列满（下游 NLS 推流慢于本帧到达）时直接丢弃这一小片，
        // 接收线程绝不阻塞——这正是"有界 + 背压"的体现。音频流式，丢一小片无碍整体转写。
        boolean accepted = conn.ring.offer(frame);
        if (!accepted) {
            log.debug("nls ring full, drop audio frame, sessionId={}", sessionId);
        }
        // 修改结束
    }

    /**
     * 结束一场面试的上行 NLS 连接。
     *
     * <p>先发 0x04 结束帧告知 NLS 音频已发完，再关连接，让 NLS 正常收尾而不是
     * 被动断连。幂等：连接不存在（从未 start 或已 stop）时直接返回。
     *
     * @param sessionId 面试会话 ID
     */
    public void stop(String sessionId) {
        UpstreamConnection conn = sessions.remove(sessionId);
        if (conn == null) {
            return;
        }
        // 从这里开始是修改的：stop 不再自己发结束帧，而是关掉环形缓冲，让消费线程端掉最后一段。
        // 为什么交给消费线程：所有 sendBinary（含 0x04 结束帧）现在都只在消费线程里执行，
        // 若 stop 自己再发一个 0x04，会与消费线程并发去写同一条 WebSocket，造成帧序错乱。
        // 这里只负责【关闭缓冲 + 唤醒 + 等待消费线程收尾】，干净退出。
        conn.ring.close();
        Thread consumer = conn.consumer;
        if (consumer != null) {
            try {
                // 消费线程被 close 唤醒后，先把 ring 里剩帧发完，再发 0x04 与 sendClose；
                // 等待上限与建连超时一致，防 NLS 网关不响应时把 stop 调用方也挂死。
                consumer.join(CONN_TIMEOUT.toMillis());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        // 修改结束
    }

    // ============================================================
    // 私有：建立上行连接
    // ============================================================
    // buildAsync(...).join()：同步等连接建好，调用方（前端 audio_start 消息处理）
    // 期望"返回时上行已就绪"，这样随后的 pushAudio 不会撞上未连接的状态。
    // ============================================================
    private UpstreamConnection open(
            String sessionId,
            Consumer<String> previewSink
    ) throws Exception {
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(GATEWAY, new NlsListener(sessionId, previewSink))
                .get(CONN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new UpstreamConnection(ws, sessionId, previewSink);
    }

    // ============================================================
    // 私有：启动该会话的上行 NLS 消费线程
    // ============================================================
    // 一场面试一条专属线程，它做且只做三件事：从 NioAudioRingBuffer 取帧、
    // sendBinary 发给 NLS、收到关闭信号后排空剩余帧、发 0x04 结束帧并关闭连接。
    // 用线程而不是线程池里有界 worker：命名"NLS-<sessionId>"便于排障时一眼锁定
    // 是哪一场的转发卡住；且它只在录音期间存活，随 audio_end / 连接关闭而终止，
    // 不会长期占用池里的共享线程。
    // ============================================================
    private void startConsumer(UpstreamConnection conn) {
        Thread worker = new Thread(
                () -> consumeAndFinish(conn),
                "NLS-" + conn.sessionId
        );
        worker.setDaemon(true);
        worker.start();
        conn.consumer = worker;
    }

    /**
     * 消费循环：取帧 → 转发；收到关闭信号且排空后发结束帧并关连接。
     *
     * <p>为什么用"带超时 take + 循环"而不是"无限 take 直到关闭"：消费线程要能
     * 周期性地感知关闭标记。take 带 200ms 超时，返回 null 且 isClosed 为真说明
     * 缓冲已排空、该收尾了；否则继续取下一帧。单线程消费保证帧序严格有序。
     */
    private void consumeAndFinish(UpstreamConnection conn) {
        try {
            for (; ; ) {
                ByteBuffer frame = conn.ring.take(200);
                if (frame != null) {
                    conn.ws.sendBinary(frame, true);
                    continue;
                }
                // 超时无帧：若缓冲已关闭且已排空，说明该收尾，结束主循环。
                if (conn.ring.isClosed()) {
                    break;
                }
            }
            // 音频发完：发 0x04 结束帧告知 NLS，再正常关闭连接。
            conn.ws.sendBinary(ByteBuffer.wrap(END_HEADER), true)
                    .get(CONN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            conn.ws.sendClose(WebSocket.NORMAL_CLOSURE, "stop")
                    .get(CONN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            log.warn("nls consumer finished with error, sessionId={}",
                    conn.sessionId, error);
        }
    }

    /**
     * 直接关闭一条"没派上用场"的上行连接（start 被并发抢先时的那个新建连接）。
     */
    private void closeWs(UpstreamConnection conn) {
        try {
            conn.ws.sendClose(WebSocket.NORMAL_CLOSURE, "superseded")
                    .get(CONN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception error) {
            log.warn("close superseded nls upstream failed, sessionId={}",
                    conn.sessionId, error);
        }
    }

    // ============================================================
    // 私有：发送 StartTranscription 指令
    // ============================================================
    // 结构严格对齐前端 openNlsSocket 里能跑通的那份：namespace=SpeechTranscriber、
    // name=StartTranscription、payload 里开中间结果与标点。唯一差别是 token 取自
    // 后端 AliyunNlsTokenService（AccessKey 不下发浏览器）。
    // ============================================================
    private void sendStart(UpstreamConnection conn) {
        try {
            String start = mapper.writeValueAsString(Map.of(
                    "header", Map.of(
                            // message_id 用纳秒时间戳保证单次会话唯一。
                            "message_id", String.valueOf(System.nanoTime()),
                            "task", "transcription",
                            "namespace", "SpeechTranscriber",
                            "name", "StartTranscription",
                            "appkey", tokenService.getAppKey(),
                            "token", tokenService.getToken()
                    ),
                    "payload", Map.of(
                            "format", "pcm",
                            "sample_rate", SAMPLE_RATE,
                            "enable_intermediate_result", true,
                            "enable_punctuation_prediction", true,
                            "enable_inverse_text_normalization", true
                    )
            ));
            conn.ws.sendText(start, true);
        } catch (Exception error) {
            log.error("send start transcription failed, sessionId={}",
                    conn.sessionId, error);
        }
    }

    // ============================================================
    // 私有：处理 NLS 返回的一帧文本消息
    // ============================================================
    // NLS 回的都是文本帧（识别分片或状态变更）。只认 TranscriptionResultChanged：
    // 提取 payload 里的字段，构造 AudioSegment 交给 AsyncTranscriptionBuffer 入队，
    // 从而走进"去重 + 有序合并"的现成管线（接续去重）；同时把文本作为预览回推前端。
    // ============================================================
    private void handleNlsText(String sessionId, String payloadJson) {
        // NLS 网络线程回调，任何异常都要接住，否则会把上行连接整个打断。
        try {
            JsonNode root = mapper.readTree(payloadJson);
            JsonNode header = root.path("header");
            if (!"TranscriptionResultChanged".equals(header.path("name").asText())) {
                // 其它消息（StartTranscription 回执、SentenceEnd 等）与分片无关，忽略。
                return;
            }
            JsonNode payload = root.path("payload");
            int segId = payload.path("seg_id").asInt(0);
            String text = payload.path("text").asText("");
            String pgs = payload.path("pgs").asText("");
            // rg / bg 是数组，取第一个元素作为起始位置（与前端 readRangeValue 一致）。
            int rg = firstOfArray(payload, "rg");
            int bg = firstOfArray(payload, "bg");
            boolean ed = payload.path("ed").asBoolean(false);

            // 关键：把 NLS 原生的分片塞进既有缓冲区去重合并，而不是新写一套拼接逻辑。
            buffer.receive(sessionId, new AsyncTranscriptionBuffer.AudioSegment(
                    segId, text, pgs, rg, bg, ed
            ));
            // 预览回推：非空文本就推给前端刷新实时显示（前端 state 用 textarea 预览）。
            // 注意：可能取到 null（并发始动竞态或已 stop 后残留），判空再回调。
            UpstreamConnection conn = sessions.get(sessionId);
            if (!text.isBlank() && conn != null && conn.previewSink != null) {
                conn.previewSink.accept(text);
            }
        } catch (Exception error) {
            log.warn("handle nls result failed, sessionId={}", sessionId, error);
        }
    }

    // 取数组字段的第一个元素；不是数组或为空时返回 0。
    private int firstOfArray(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (value.isArray() && !value.isEmpty()) {
            return value.get(0).asInt(0);
        }
        return 0;
    }

    // ============================================================
    // 上行连接封装 —— WebSocket + 回调预览出口 + NIO 环形缓冲 + 消费线程
    // ============================================================
    private final class UpstreamConnection {
        final WebSocket ws;
        final String sessionId;
        final Consumer<String> previewSink;
        // 从这里开始是新增的：每个会话一个音频环形缓冲 + 一条专属消费线程引用。
        // ring 容量 64：按 16k 16bit 单声道、浏览器每片 ~4096 采样(约 128ms)估，
        // 64 槽 ≈ 8 秒缓冲余量，既抗 NLS 慢，又不至于积压太多造成明显时延。
        final NioAudioRingBuffer ring = new NioAudioRingBuffer(64);
        volatile Thread consumer;
        // 新增结束

        UpstreamConnection(
                WebSocket ws,
                String sessionId,
                Consumer<String> previewSink
        ) {
            this.ws = ws;
            this.sessionId = sessionId;
            this.previewSink = previewSink;
        }
    }

    // ============================================================
    // NLS 监听器 —— 把 NLS 的回包路由到 handleNlsText
    // ============================================================
    private final class NlsListener implements WebSocket.Listener {
        private final String sessionId;
        private final Consumer<String> previewSink;

        NlsListener(String sessionId, Consumer<String> previewSink) {
            this.sessionId = sessionId;
            this.previewSink = previewSink;
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            handleNlsText(sessionId, data.toString());
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("nls upstream error, sessionId={}", sessionId, error);
        }
    }
}