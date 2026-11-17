package com.schemaplexai.web.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.schemaplexai.web.handler.WebSocketHandler;
import com.schemaplexai.web.config.SecurityProperties;
import lombok.RequiredArgsConstructor;

/**
 * WebSocket 配置
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketHandler webSocketHandler;
    private final WebSocketAuthHandshakeInterceptor webSocketAuthHandshakeInterceptor;
    private final SecurityProperties securityProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws")
                .addInterceptors(webSocketAuthHandshakeInterceptor)
                .setAllowedOrigins(securityProperties.getAllowedOrigins().toArray(String[]::new));
    }
}
