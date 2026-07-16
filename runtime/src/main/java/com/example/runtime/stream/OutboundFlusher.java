package com.example.runtime.stream;

import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.ws.OutboundMessage;
import com.example.runtime.ws.RuntimeWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Раз в runtime.flush-interval-ms проходит по всем активным сессиям и отправляет
 * накопленные обновления тегов/свойств одним WS-фреймом на сессию. Именно это
 * даёт "максимально оперативную" доставку при большом числе часто обновляющихся
 * тегов без лавины мелких сообщений на каждое отдельное изменение.
 */
@Component
public class OutboundFlusher {

    private final RuntimeSessionStore sessionStore;
    private final RuntimeWebSocketHandler webSocketHandler;

    public OutboundFlusher(RuntimeSessionStore sessionStore, RuntimeWebSocketHandler webSocketHandler) {
        this.sessionStore = sessionStore;
        this.webSocketHandler = webSocketHandler;
    }

    @Scheduled(fixedRateString = "${runtime.flush-interval-ms:40}")
    public void flush() {
        for (RuntimeSession session : sessionStore.all()) {
            // Сессия создаётся по REST раньше, чем фронт успевает подключить WS.
            // Дренировать буфер, пока слать некуда, нельзя — drainAll() необратим,
            // и всё накопленное (включая replay последних значений) пропало бы.
            WebSocketSession wsSession = session.getWebSocketSession();
            if (wsSession == null || !wsSession.isOpen()) {
                continue;
            }
            SessionOutboundBuffer buffer = session.getOutboundBuffer();
            if (buffer.isEmpty()) {
                continue;
            }
            SessionOutboundBuffer.Drained drained = buffer.drainAll();
            if (drained.isEmpty()) {
                continue;
            }
            webSocketHandler.send(session, new OutboundMessage(drained.tags(), drained.properties()));
        }
    }
}
