package com.schemaplexai.web.websocket;

import com.schemaplexai.service.auth.handler.TokenHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * WebSocket 握手鉴权拦截器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthHandshakeInterceptor implements HandshakeInterceptor {

    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_TENANT_ID = "tenantId";
    private static final String WS_TICKET_PARAM = "ws_ticket";

    private final TokenHandler tokenHandler;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }

        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        String wsTicket = httpRequest.getParameter(WS_TICKET_PARAM);
        if (!StringUtils.hasText(wsTicket)) {
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }

        TokenHandler.WsTicketPayload payload = tokenHandler.consumeWsTicket(wsTicket);
        if (payload == null
                || !StringUtils.hasText(payload.userId())
                || !StringUtils.hasText(payload.tenantId())) {
            log.warn("WebSocket 握手 ticket 校验失败");
            reject(response, HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATTR_USER_ID, payload.userId());
        attributes.put(ATTR_TENANT_ID, payload.tenantId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private void reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.getServletResponse().setStatus(status.value());
        }
    }
}
