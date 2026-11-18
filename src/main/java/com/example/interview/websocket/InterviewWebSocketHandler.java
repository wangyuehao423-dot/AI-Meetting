package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

// ============================================================
// 面试会话的实时通道（心跳保活）
// ============================================================
// 职责只有一件：维持 /ws/interviews/{sessionId} 这条长连接可用。
// 前端进入答题页时连上来，每 10 秒发一次 {"type":"ping"}，这里回 {"type":"pong"}。
//
// 为什么需要它：Nginx / 家用路由器 / 运营商 NAT 都会回收"一段时间没有数据往来"的
// 长连接，回收后前端并不知情，直到下一次真正要发消息才发现连接已死。
// 定时心跳让连接始终有字节流动，是最省事也最可靠的做法。
// ============================================================
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper;

    public InterviewWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        JsonNode root = mapper.readTree(message.getPayload());
        if (!"ping".equals(root.path("type").asText())) {
            // 其他类型的消息当前没有业务含义，静默忽略即可。
            // 不做异常抛出：前端版本可能比后端新，多出来的消息类型不该让连接被断掉。
            return;
        }
        session.sendMessage(new TextMessage(
                mapper.writeValueAsString(Map.of("type", "pong"))
        ));
    }
}
