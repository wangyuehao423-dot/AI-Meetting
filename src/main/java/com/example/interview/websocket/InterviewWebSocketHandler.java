package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

// ============================================================
// WebSocket 处理器 —— 语音转写分片的接收与转发
// ============================================================
// 职责：
//   1. 连接建立：读取 sessionId，创建 TranscriptionSessionContext，
//      启动 AsyncTranscriptionBuffer 的异步消费线程
//   2. 消息接收：解析 JSON 字段，构造 AudioSegment 入队
//      （入队用带超时的 offer，队列满时丢弃分片而不是阻塞接收线程）
//   3. 心跳：响应前端 ping，返回 pong，保持长连接不被网络设备断开
//   4. 连接关闭：清理队列和上下文，避免内存泄漏
//
// 与"十六点一"同步版的区别：
//   本版本是最终实现——接收与消费解耦，接收线程只负责入队，
//   合并去重由 AsyncTranscriptionBuffer.consume 在独立线程池完成。
//   好处是 ASR 分片突增时不会拖垮 WebSocket 接收线程。
// ============================================================
@Component  // 声明为 Spring 组件，由 WebSocketConfig 注册到处理链
public class InterviewWebSocketHandler
        extends TextWebSocketHandler {

    // Jackson JSON 处理器：用于解析收到的消息和序列化推送结果
    private final ObjectMapper mapper;
    // 异步音频缓冲：接收与消费解耦的核心
    private final AsyncTranscriptionBuffer buffer;
    // 从这里开始是新增的：阿里云 NLS 实时转写【后端中继】。
    // 前端把录音得到的裸 PCM 以二进制帧送来，本处理器转交给它推给 NLS；
    // NLS 的识别分片由它落回 AsyncTranscriptionBuffer 去重合并。原来"后端不解析音频、
    // 只收文本分片"的缺口就是靠它补上的（见 AliyunNlsRealtimeRelay 类头注释）。
    private final AliyunNlsRealtimeRelay nlsRelay;
    // 新增结束

    // 面试会话 ID -> 转写上下文的映射
    // key：面试会话 ID（握手拦截器从 URL 解析后放入 attributes）
    private final Map<String, TranscriptionSessionContext> contexts =
            new ConcurrentHashMap<>();

    /**
     * 构造器注入依赖。
     *
     * @param mapper Jackson JSON 处理器（Spring Boot 自动配置）
     * @param buffer 异步音频缓冲，负责分片入队与异步消费
     * @param nlsRelay 阿里云 NLS 后端中继，负责把前端音频推流到 NLS
     */
    public InterviewWebSocketHandler(
            ObjectMapper mapper,
            AsyncTranscriptionBuffer buffer,
            AliyunNlsRealtimeRelay nlsRelay
    ) {
        this.mapper = mapper;
        this.buffer = buffer;
        this.nlsRelay = nlsRelay;
    }

    // ============================================================
    // 连接建立回调
    // ============================================================
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = sessionId(session);
        TranscriptionSessionContext context =
                new TranscriptionSessionContext();
        contexts.put(sessionId, context);
        // 启动该会话的异步消费：
        // 分片从队列取出后由 mergeSegment 去重合并成最终 transcript
        buffer.consume(sessionId, context);
    }

    // ============================================================
    // 消息接收回调
    // ============================================================
    // 前端消息格式（JSON）：
    //   {
    //     "type": "transcription",       // 消息类型，非 transcription 类型直接忽略
    //     "seg_id": 0,                   // 分片序号（必填，整数）
    //     "text": "今天天气真不错",       // 分片文本（可选，默认空字符串）
    //     "pgs": "speaking",             // 分片状态（可选，如 "speaking"/"silence"）
    //     "rg": [0, 100],                // 音频范围数组（可选，取第一个元素）
    //     "bg": [0, 50],                 // 分片开始时间数组（可选，取第一个元素）
    //     "ed": true                     // 是否最终结果（可选，默认 false）
    //   }
    //   心跳消息：{"type": "ping"}
    // ============================================================
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        // ---- 步骤1：解析收到的 JSON 消息 ----
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText();

        // ---- 步骤2：心跳处理 ----
        // 前端每 10 秒发一次 ping，这里回 pong 保持连接活跃
        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(
                            Map.of("type", "pong")
                    )
            ));
            return;
        }

        // ---- 步骤2.5：录音控制消息 ----
        // audio_start：前端把麦克风收到手的瞬间发来，后端立即建立上行 NLS 连接；
        // audio_end：前端停止录音时发来，后端发 0x04 结束帧并关闭上行。
        // 这两种是"二进制音频帧"的外围控制，天然是文本消息，所以夹在心跳与分片之间处理。
        if ("audio_start".equals(type)) {
            nlsRelay.start(sessionId(session), previewSink(session));
            return;
        }
        if ("audio_end".equals(type)) {
            nlsRelay.stop(sessionId(session));
            return;
        }

        // ---- 步骤3：消息类型过滤 ----
        // 只处理 type="transcription" 的消息，其他类型静默忽略
        if (!"transcription".equals(type)) {
            return;
        }

        // ---- 步骤4：提取各字段 ----
        // seg_id：分片序号，必须存在且为整数，否则抛异常
        int segId = requiredInt(root, "seg_id");
        // text：分片文本内容，可选字段，缺失时默认为空字符串
        String text = root.path("text").asText("");
        // pgs：分片状态，如 "speaking" 表示正在说话
        String pgs = root.path("pgs").asText("");
        // rg：音频范围位置，可能是数组 [start, end]，取第一个值
        int rg = readRangeValue(root, "rg");
        // bg：分片开始时间，可能是数组 [start]，取第一个值
        int bg = readRangeValue(root, "bg");
        // ed：是否最终结果，支持布尔值和字符串 "true"/"false"
        boolean ed = readBoolean(root, "ed");

        // ---- 步骤5：构造分片并入队 ----
        // receive 内部用带超时的 offer（最多等 200ms），队列满返回 false。
        // 此时直接丢弃该分片——ASR 后续还会推送更准确的修正结果，
        // 丢一两个中间分片不会影响最终转写，绝不能阻塞接收线程。
        boolean accepted = buffer.receive(
                sessionId(session),
                new AsyncTranscriptionBuffer.AudioSegment(
                        segId, text, pgs, rg, bg, ed
                )
        );
        if (!accepted) {
            // 队列已满：丢弃当前分片，等待后续分片自然补齐
            return;
        }
    }

    // ============================================================
    // 二进制消息回调 —— 前端送来的裸 PCM 音频 → 转发给阿里云 NLS
    // ============================================================
    // 前端 NlsTranscriptionClient 采集麦克风并降采样成 16k 单声道 PCM 后，
    // 以二进制帧经本连接直接送来（不含任何 NLS 帧头）。这里把原始字节原样
    // 交给 AliyunNlsRealtimeRelay，由它在头部补上 NLS 帧头再推流。
    // 为什么用异步中继面向 NLS 网络线程回调：pushAudio 只做 sendBinary 不入队，
    // 接收线程不会被 NLS 的等待卡住，保证音频流不会被积压拖断。
    // ============================================================
    @Override
    protected void handleBinaryMessage(
            WebSocketSession session,
            BinaryMessage message
    ) {
        ByteBuffer payload = message.getPayload();
        byte[] audio = new byte[payload.remaining()];
        payload.get(audio);
        nlsRelay.pushAudio(sessionId(session), audio);
    }

    // ============================================================
    // 连接关闭回调
    // ============================================================
    // 清理队列和上下文，释放内存。否则已关闭的连接会一直驻留在内存中，
    // 长期运行形成内存泄漏。
    // ============================================================
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            org.springframework.web.socket.CloseStatus status
    ) {
        String sessionId = sessionId(session);
        // 从这里开始是新增的：关连接时一并关闭该会话的 NLS 上行。
        // 用户中途离开页面 / 整场结束都会走到这里，若漏关，NLS 上行连接就会一直
        // 挂着，占着阿里云会话数配额。stop 幂等，重复调用无副作用。
        nlsRelay.stop(sessionId);
        // 新增结束
        // 清理该会话的缓冲队列：close 会写入关闭标记，
        // 该会话的消费循环随即自行退出并把线程归还线程池
        buffer.close(sessionId);
        // 清理转写上下文
        contexts.remove(sessionId);
    }

    // ============================================================
    // 私有方法：从握手 attributes 读取面试会话 ID
    // ============================================================
    // sessionId 由 WebSocketConfig 的握手拦截器在握手阶段解析并放入
    // attributes，这里直接读取即可，无需再解析路径。
    // ============================================================
    private String sessionId(WebSocketSession session) {
        return (String) session.getAttributes().get("sessionId");
    }

    // ============================================================
    // 私有方法：构造"预览文本回推"回调，交给 AliyunNlsRealtimeRelay
    // ============================================================
    // NLS 的识别结果由中继落进缓冲区合并，同时还要把临时文本推回前端，
    // 让界面的"语音转写"预览随说话实时刷新。回调捕获当前 WebSocketSession，
    // 以 {type:'asr_preview', text} 推回。session 可能已被前端关闭（如用户中途
    // 停止录音后 NLS 仍回了一帧），sendMessage 抛错即静默丢弃，不影响上行。
    // ============================================================
    private Consumer<String> previewSink(WebSocketSession session) {
        return text -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(
                            mapper.writeValueAsString(
                                    Map.of("type", "asr_preview", "text", text)
                            )
                    ));
                }
            } catch (Exception ignored) {
                // 前端已断开预览连接，放弃回推。
            }
        };
    }

    // ============================================================
    // 私有方法：必填整数字段提取
    // ============================================================
    // 如果字段不存在或不能转换为整数，抛出 IllegalArgumentException。
    // 这种"严格校验"的设计确保我们不会把无效数据传给 buffer。
    // ============================================================
    private int requiredInt(JsonNode root, String field) {
        if (!root.has(field) || !root.get(field).canConvertToInt()) {
            throw new IllegalArgumentException(
                    "missing or invalid " + field
            );
        }
        return root.get(field).asInt();
    }

    // ============================================================
    // 私有方法：数组类型字段提取（取第一个元素）
    // ============================================================
    // ASR 引擎返回的 rg（音频范围）和 bg（开始时间）可能是数组格式，
    // 如 [0, 100]。我们只取数组的第一个元素作为位置的起始值。
    // ============================================================
    private int readRangeValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return 0;
        }
        return value.get(0).asInt(0);
    }

    // ============================================================
    // 私有方法：布尔值提取（支持多种格式）
    // ============================================================
    // ASR 引擎可能以不同格式返回布尔值：
    //   - 标准布尔：true / false
    //   - 字符串："true" / "false" / "1" / "0"
    // 此方法兼容上述所有格式。
    // ============================================================
    private boolean readBoolean(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            return false;  // 字段不存在，默认 false
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return "true".equalsIgnoreCase(value.asText());
    }
}