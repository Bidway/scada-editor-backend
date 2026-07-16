package com.example.runtime.config;

import com.example.runtime.ws.RuntimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Сырой (без STOMP/SockJS) WebSocket — минимальный протокольный оверхед для
 * канала, где ожидается большой поток обновлений тегов. Путь: /ws/runtime/{sessionId}.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RuntimeWebSocketHandler runtimeWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(runtimeWebSocketHandler, "/ws/runtime/*")
                .setAllowedOriginPatterns("*");
    }
}
