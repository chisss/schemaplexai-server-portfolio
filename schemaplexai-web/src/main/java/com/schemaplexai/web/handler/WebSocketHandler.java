package com.schemaplexai.web.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket处理器 - Agent执行进度、通知推送
 */
@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {

    /** 在线会话管理: sessionId → WebSocketSession */
    private static final ConcurrentHashMap<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.put(session.getId(), session);
        log.info("WebSocket连接建立: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到WebSocket消息: sessionId={}, payload={}", session.getId(), message.getPayload());
        // 处理订阅/取消订阅等指令
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SESSIONS.remove(session.getId());
        log.info("WebSocket连接关闭: sessionId={}, status={}", session.getId(), status);
    }

    /**
     * 向指定会话发送消息
     */
    public void sendMessage(String sessionId, String message) {
        WebSocketSession session = SESSIONS.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
            } catch (IOException e) {
                log.error("WebSocket消息发送失败: sessionId={}", sessionId, e);
            }
        }
    }

    /**
     * 广播消息给所有在线会话
     */
    public void broadcast(String message) {
        TextMessage textMessage = new TextMessage(message);
        SESSIONS.values().stream()
                .filter(WebSocketSession::isOpen)
                .forEach(session -> {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        log.error("WebSocket广播失败: sessionId={}", session.getId(), e);
                    }
                });
    }
}
