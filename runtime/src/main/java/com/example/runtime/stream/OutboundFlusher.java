package com.example.runtime.stream;

import com.example.runtime.config.RuntimeProperties;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.ws.OutboundMessage;
import com.example.runtime.ws.RuntimeWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Раз в runtime.flush-interval-ms проходит по всем активным сессиям и отправляет
 * накопленные обновления тегов/свойств одним WS-фреймом на сессию. Именно это
 * даёт "максимально оперативную" доставку при большом числе часто обновляющихся
 * тегов без лавины мелких сообщений на каждое отдельное изменение.
 * <p>
 * <b>Почему отправка в пуле.</b> Раньше и обход сессий, и блокирующий
 * {@code sendMessage} выполнял единственный поток планировщика (пул задач Spring по
 * умолчанию — один поток). Любой клиент с забитым TCP-окном — оператор по VPN, ноутбук
 * в спящем режиме — останавливал рассылку всем остальным сессиям, а раз в тот же поток
 * ходит и {@code RuntimeSessionReaper}, вместе с ней вставала и уборка сессий. Теперь
 * поток планировщика только раскладывает работу, а отправку делает пул.
 * <p>
 * <b>Почему tryLock.</b> Если предыдущая отправка этой сессии ещё идёт, тик
 * пропускается: данные остаются в буфере и уедут следующим кадром. Так медленный
 * клиент не копит очередь задач, а его обновления естественным образом
 * склеиваются — при {@code fixedRate} задачи иначе накапливались бы быстрее,
 * чем исполнялись.
 */
@Component
@Slf4j
public class OutboundFlusher {

    private final RuntimeSessionStore sessionStore;
    private final RuntimeWebSocketHandler webSocketHandler;
    private final RuntimeProperties properties;

    private ThreadPoolExecutor flushExecutor;

    public OutboundFlusher(RuntimeSessionStore sessionStore,
                           RuntimeWebSocketHandler webSocketHandler,
                           RuntimeProperties properties) {
        this.sessionStore = sessionStore;
        this.webSocketHandler = webSocketHandler;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        int threads = properties.getFlushThreads();
        flushExecutor = new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(64, threads * 64)),
                r -> {
                    Thread t = new Thread(r, "ws-flush");
                    t.setDaemon(true);
                    return t;
                },
                // Отбросить задачу безопасно: буфер не тронут, кадр уедет следующим тиком.
                new ThreadPoolExecutor.DiscardPolicy());
        log.info("OutboundFlusher started: {} sender thread(s)", threads);
    }

    @PreDestroy
    void shutdown() {
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
        }
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
            if (session.getOutboundBuffer().isEmpty()) {
                continue;
            }
            flushExecutor.execute(() -> flushSession(session));
        }
    }

    private void flushSession(RuntimeSession session) {
        ReentrantLock lock = session.getSendLock();
        // Предыдущий кадр этой сессии ещё уходит — пропускаем тик, данные остаются в буфере.
        if (!lock.tryLock()) {
            return;
        }
        try {
            // Дренаж только под захваченным локом: drainAll() необратим, и потерять
            // содержимое из-за неудачной отправки нельзя было бы восстановить.
            SessionOutboundBuffer.Drained drained = session.getOutboundBuffer().drainAll();
            if (drained.isEmpty()) {
                return;
            }
            webSocketHandler.send(session, new OutboundMessage(drained.tags(), drained.properties()));
        } finally {
            lock.unlock();
        }
    }
}
