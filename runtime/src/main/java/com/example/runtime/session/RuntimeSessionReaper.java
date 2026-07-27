package com.example.runtime.session;

import com.example.runtime.config.RuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;

/**
 * Закрывает брошенные сессии: REST создаёт сессию, но WebSocket может не подключиться
 * никогда. Без уборки {@link RuntimeSessionStore} и подписки в {@code TagValueRouter.tagStates}
 * копились бы бесконечно, а каждая такая сессия продолжала бы получать значения тегов в буфер.
 * Признак брошенной — нет живого WS дольше настроенного тайм-аута (у сессии есть {@code createdAt}).
 */
@Component
@Slf4j
public class RuntimeSessionReaper {

    private final RuntimeSessionStore sessionStore;
    private final RuntimeSessionService sessionService;
    private final Duration abandonedTimeout;

    public RuntimeSessionReaper(RuntimeSessionStore sessionStore,
                                RuntimeSessionService sessionService,
                                RuntimeProperties properties) {
        this.sessionStore = sessionStore;
        this.sessionService = sessionService;
        this.abandonedTimeout = Duration.ofMinutes(properties.getSession().getAbandonedTimeoutMinutes());
    }

    @Scheduled(fixedDelayString = "${runtime.session.reaper-interval-ms:60000}")
    public void reapAbandonedSessions() {
        Instant cutoff = Instant.now().minus(abandonedTimeout);
        for (RuntimeSession session : sessionStore.all()) {
            WebSocketSession ws = session.getWebSocketSession();
            boolean noLiveWs = ws == null || !ws.isOpen();
            if (noLiveWs && session.getCreatedAt().isBefore(cutoff)) {
                log.info("Reaping abandoned runtime session {} (no WebSocket, created {})",
                        session.getId(), session.getCreatedAt());
                sessionService.closeSession(session.getId());
            }
        }
    }
}
