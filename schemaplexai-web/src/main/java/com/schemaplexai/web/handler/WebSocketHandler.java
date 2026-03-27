package com.schemaplexai.web.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket处理器 - Agent执行进度、通知推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHandler extends TextWebSocketHandler {

    /** 在线会话管理: sessionId → WebSocketSession */
    private static final ConcurrentHashMap<String, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();
    private static final String MSG_TYPE_EXECUTION_INPUT = "EXECUTION_INPUT";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_TENANT_ID = "tenantId";

    private final ObjectMapper objectMapper;
    private final ExecutionEventStreamService executionEventStreamService;
    private final AgentService agentService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SESSIONS.put(session.getId(), session);
        log.info("WebSocket连接建立: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到WebSocket消息: sessionId={}, payload={}", session.getId(), message.getPayload());
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    message.getPayload(),
                    new TypeReference<Map<String, Object>>() {}
            );
            Object typeObj = payload.get("type");
            if (typeObj == null) {
                sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"缺少 type 字段\"}");
                return;
            }

            String type = String.valueOf(typeObj);
            if (MSG_TYPE_EXECUTION_INPUT.equals(type)) {
                handleExecutionInput(session, payload);
                return;
            }

            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"不支持的消息类型\"}");
        } catch (Exception e) {
            log.warn("解析WebSocket消息失败: sessionId={}", session.getId(), e);
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"消息格式错误\"}");
        }
    }

    private void handleExecutionInput(WebSocketSession session, Map<String, Object> payload) {
        String userId = asString(session.getAttributes().get(ATTR_USER_ID));
        String tenantId = asString(session.getAttributes().get(ATTR_TENANT_ID));
        if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"会话未认证\"}");
            return;
        }

        String agentId = asString(payload.get("agentId"));
        String executionId = asString(payload.get("executionId"));
        String message = asString(payload.get("message"));
        Object options = payload.get("options");

        if (agentId == null || agentId.isBlank()) {
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"agentId 不能为空\"}");
            return;
        }
        if (executionId == null || executionId.isBlank()) {
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"executionId 不能为空\"}");
            return;
        }
        if (message == null || message.isBlank()) {
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"message 不能为空\"}");
            return;
        }

        try {
            SecurityUtil.setCurrentUserId(userId);
            SecurityUtil.setCurrentTenantId(tenantId);
            // 权限校验：要求 agentId + executionId 在当前租户可访问
            agentService.getExecution(agentId, executionId);

            executionEventStreamService.publish(AgentExecutionEvent.builder()
                    .eventType("USER_INPUT")
                    .executionId(executionId)
                    .message(message)
                    .payload(options)
                    .timestamp(Instant.now())
                    .build());

            sendMessage(session.getId(), "{\"type\":\"ACK\",\"eventType\":\"USER_INPUT\"}");
        } catch (BusinessException ex) {
            log.warn("WebSocket 执行输入校验失败: agentId={}, executionId={}, userId={}, tenantId={}",
                    agentId, executionId, userId, tenantId);
            sendMessage(session.getId(), "{\"type\":\"ERROR\",\"message\":\"无权访问该执行会话\"}");
        } finally {
            SecurityUtil.clear();
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
