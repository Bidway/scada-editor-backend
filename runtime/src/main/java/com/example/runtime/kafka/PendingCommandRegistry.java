package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ожидающие подтверждения команды: {@code commandId} → future исхода.
 * <p>
 * Связывает две стороны разговора со шлюзом, которые идут через разные топики:
 * {@link CommandProducer} кладёт команду в {@code scada-commands} и регистрирует здесь
 * ожидание, {@link CommandResultConsumer} читает {@code scada-command-results} и этим
 * ожиданием разрешает. Корреляция — по {@code commandId}, который генерирует продюсер.
 * <p>
 * <b>Почему у каждой записи свой таймер.</b> Ответа может не быть вовсе: шлюз лежит,
 * топик результатов не создан, сообщение потеряно. Без срока годности запись висела бы
 * вечно, а вызывающий — ждал бы future, который никто не завершит. Поэтому регистрация
 * сразу планирует разрешение по таймауту; фонового «подметальщика» нет, потому что
 * команд единицы в минуту, и таймер на каждую дешевле периодического обхода карты.
 */
@Component
@Slf4j
public class PendingCommandRegistry {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiry;
    private final long timeoutMs;

    public PendingCommandRegistry(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getCommandTimeoutMs();
        this.expiry = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "command-result-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        expiry.shutdownNow();
        // Никого не оставляем висеть на выключении: все, кто ещё ждёт, получают честное
        // «исход неизвестен» вместо future, который уже некому завершить.
        pending.keySet().forEach(commandId ->
                settle(commandId, CommandOutcome.failure(CommandOutcome.NO_CONFIRMATION,
                        "Runtime останавливается, ответ шлюза не дождались")));
    }

    /**
     * Регистрирует ожидание исхода команды.
     *
     * @return future, который завершится ответом шлюза либо, по истечении таймаута,
     *         статусом {@link CommandOutcome#NO_CONFIRMATION}. Не завершается исключением.
     */
    public CompletableFuture<CommandOutcome> awaiting(String commandId, String tagId) {
        CompletableFuture<CommandOutcome> future = new CompletableFuture<>();
        ScheduledFuture<?> timer = expiry.schedule(
                () -> {
                    if (settle(commandId, CommandOutcome.failure(CommandOutcome.NO_CONFIRMATION,
                            "Шлюз не ответил за " + timeoutMs + " мс"))) {
                        log.warn("Команда {} по тегу '{}': подтверждение от шлюза не получено за {} мс",
                                commandId, tagId, timeoutMs);
                    }
                },
                timeoutMs, TimeUnit.MILLISECONDS);
        pending.put(commandId, new Pending(future, timer));
        return future;
    }

    /**
     * Разрешает ожидание исходом от шлюза. Результат по неизвестному {@code commandId}
     * молча игнорируется: это либо дубль, либо ответ на команду другого потребителя
     * того же топика (у Monitor Srv свои команды), либо ответ, пришедший после таймаута.
     */
    public void complete(String commandId, CommandOutcome outcome) {
        settle(commandId, outcome);
    }

    /** @return {@code true}, если ожидание разрешено именно этим вызовом */
    boolean settle(String commandId, CommandOutcome outcome) {
        if (commandId == null) {
            return false;
        }
        Pending p = pending.remove(commandId);
        if (p == null) {
            return false;
        }
        p.timer.cancel(false);
        return p.future.complete(outcome);
    }

    private record Pending(CompletableFuture<CommandOutcome> future, ScheduledFuture<?> timer) {
    }
}
