package com.example.interview.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// ============================================================
// WebSocket 配置 —— 注册语音转写路由
// ============================================================
// 前端连接地址：ws://localhost:8080/ws/interviews/{sessionId}
//
// 握手拦截器的作用：
//   把 URL 中的 sessionId 解析出来放入 attributes，
//   后续 handleTextMessage 直接从 session 的 attributes 读取，
//   这样处理器不需要自己解析路径，职责更单一。
//
// 注意：JWT 鉴权也可以放在握手拦截器中（在 beforeHandshake 里校验
// Authorization 头），教学版从简，生产环境建议补上。
// ============================================================
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterviewWebSocketHandler handler;

    public WebSocketConfig(InterviewWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                        handler,
                        "/ws/interviews/{sessionId}"
                )
                .setAllowedOrigins("*")  // 教学环境放开跨域；生产应按前端域名收敛
                .addInterceptors(
                        new HandshakeInterceptor() {
                            @Override
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler wsHandler,
                                    Map<String, Object> attributes
                            ) {
                                // 从路径 /ws/interviews/{sessionId} 中截取 sessionId
                                String path = request.getURI().getPath();
                                String sessionId = path.substring(
                                        path.lastIndexOf('/') + 1
                                );
                                attributes.put("sessionId", sessionId);
                                return true;
                            }

                            @Override
                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler wsHandler,
                                    Exception exception
                            ) {
                                // 握手后无需处理
                            }
                        }
                );
    }
}