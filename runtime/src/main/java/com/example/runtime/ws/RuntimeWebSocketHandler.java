package com.example.runtime.ws;

import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionService;
import com.example.runtime.stream.PropertyUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

/**
 * Один WS-канал на сессию мониторинга: сюда пишутся батчи тегов/свойств
 * (см. OutboundFlusher), отсюда же принимаются ACTION от фронта (нажатие кнопки).
 * Специально raw WebSocket, без STOMP/SockJS — минимальный протокольный оверхед,
 * так как тегов может быть много и они могут обновляться очень часто.
 */
@Component
@Slf4j
public class RuntimeWebSocketHandler extends TextWebSocketHandler {

    private final RuntimeSessionService sessionService;
    private final ObjectMapper objectMapper;

    public RuntimeWebSocketHandler(RuntimeSessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        String sessionId = extractSessionId(wsSession);
        RuntimeSession session = sessionService.getSession(sessionId);
        if (session == null) {
            closeQuietly(wsSession, CloseStatus.NOT_ACCEPTABLE.withReason("Unknown runtime session"));
            return;
        }
        WebSocketSession existing = session.getWebSocketSession();
        if (existing != null && existing.isOpen()) {
            // Одна WS на сессию: раньше второе подключение молча перезаписывало первое,
            // и закрытие любого из двух рвало сессию для обоих. Теперь новое отклоняем.
            log.warn("Runtime session {} already has an active WebSocket; rejecting the new connection", sessionId);
            closeQuietly(wsSession, CloseStatus.NOT_ACCEPTABLE.withReason("Session already has an active connection"));
            return;
        }
        session.setWebSocketSession(wsSession);
        log.info("WebSocket connected for runtime session {}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String sessionId = extractSessionId(wsSession);
        InboundMessage inbound;
        try {
            inbound = objectMapper.readValue(message.getPayload(), InboundMessage.class);
        } catch (Exception e) {
            log.warn("Malformed message on session {}: {}", sessionId, e.getMessage());
            return;
        }

        if ("ACTION".equalsIgnoreCase(inbound.getType())) {
            List<PropertyUpdate> changed = sessionService.handleAction(sessionId, inbound.getScriptId());
            if (!changed.isEmpty()) {
                RuntimeSession session = sessionService.getSession(sessionId);
                if (session != null) {
                    send(session, new OutboundMessage(null, changed));
                }
            }
        } else {
            log.warn("Unknown inbound message type '{}' on session {}", inbound.getType(), sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        String sessionId = extractSessionId(wsSession);
        RuntimeSession session = sessionService.getSession(sessionId);
        // Рвём сессию только если ушёл ИМЕННО текущий WS. Если это отклонённое/устаревшее
        // подключение (см. afterConnectionEstablished), его закрытие не должно трогать живую сессию.
        if (session != null && session.getWebSocketSession() != wsSession) {
            log.info("Stale WebSocket closed for runtime session {} ({}); session kept", sessionId, status);
            return;
        }
        sessionService.closeSession(sessionId);
        log.info("WebSocket closed for runtime session {} ({})", sessionId, status);
    }

    /** Используется и OutboundFlusher (батч), и обработчиком ACTION (мгновенный ответ). */
    public void send(RuntimeSession session, OutboundMessage message) {
        WebSocketSession wsSession = session.getWebSocketSession();
        if (wsSession == null || !wsSession.isOpen()) {
            return;
        }
        session.getSendLock().lock();
        try {
            String json = objectMapper.writeValueAsString(message);
            wsSession.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.warn("Failed to send WS message: {}", e.getMessage());
        } finally {
            session.getSendLock().unlock();
        }
    }

    private String extractSessionId(WebSocketSession wsSession) {
        String path = wsSession.getUri() != null ? wsSession.getUri().getPath() : "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private void closeQuietly(WebSocketSession wsSession, CloseStatus status) {
        try {
            wsSession.close(status);
        } catch (Exception ignored) {
        }
    }
}
