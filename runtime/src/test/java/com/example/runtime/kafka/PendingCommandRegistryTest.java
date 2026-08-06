package com.example.runtime.kafka;

import com.example.runtime.config.KafkaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Корреляция «команда → исход». Ошибка здесь означает не сбой, а <b>молчание</b>:
 * оператор ждёт отчёта о применении набора, а future никто не завершает.
 */
class PendingCommandRegistryTest {

    private static PendingCommandRegistry registry(long timeoutMs) {
        KafkaProperties props = new KafkaProperties();
        props.setCommandTimeoutMs(timeoutMs);
        return new PendingCommandRegistry(props);
    }

    @Test
    @DisplayName("ответ шлюза разрешает ожидание")
    void resultCompletesTheWait() throws Exception {
        PendingCommandRegistry registry = registry(5000);
        CompletableFuture<CommandOutcome> future = registry.awaiting("cmd-1", "tag");

        registry.complete("cmd-1", CommandOutcome.applied("Записано значение true"));

        CommandOutcome outcome = future.get(1, TimeUnit.SECONDS);
        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.status()).isEqualTo(CommandOutcome.APPLIED);
    }

    @Test
    @DisplayName("молчание шлюза завершается статусом «исход неизвестен», а не отказом")
    void silenceBecomesNoConfirmation() throws Exception {
        PendingCommandRegistry registry = registry(60);
        CompletableFuture<CommandOutcome> future = registry.awaiting("cmd-2", "tag");

        CommandOutcome outcome = future.get(2, TimeUnit.SECONDS);

        // Команда могла и примениться — ответ просто не дошёл. Показывать это оператору
        // как отказ значило бы врать в другую сторону.
        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.status()).isEqualTo(CommandOutcome.NO_CONFIRMATION);
        assertThat(outcome.isKnown()).isFalse();
    }

    @Test
    @DisplayName("отказ шлюза доносит свой код причины")
    void gatewayStatusIsCarriedThrough() throws Exception {
        PendingCommandRegistry registry = registry(5000);
        CompletableFuture<CommandOutcome> future = registry.awaiting("cmd-3", "tag");

        registry.complete("cmd-3",
                CommandOutcome.fromGateway(false, "REJECTED_NOT_WRITABLE", "Узел только на чтение"));

        CommandOutcome outcome = future.get(1, TimeUnit.SECONDS);
        // Ради этого шлюз и заводил дробные статусы: «канала нет» чинит инженер,
        // «нет связи» проходит само.
        assertThat(outcome.status()).isEqualTo("REJECTED_NOT_WRITABLE");
        assertThat(outcome.isKnown()).isTrue();
    }

    @Test
    @DisplayName("поздний и повторный ответ ничего не ломает")
    void lateOrDuplicateResultIsIgnored() throws Exception {
        PendingCommandRegistry registry = registry(5000);
        CompletableFuture<CommandOutcome> future = registry.awaiting("cmd-4", "tag");
        registry.complete("cmd-4", CommandOutcome.applied("ок"));

        // Дубль доставки, ответ на чужую команду, ответ после таймаута — всё сюда.
        registry.complete("cmd-4", CommandOutcome.failure("FAILED_WRITE", "поздний дубль"));
        registry.complete("unknown-command", CommandOutcome.applied("чужая"));

        assertThat(future.get(1, TimeUnit.SECONDS).status()).isEqualTo(CommandOutcome.APPLIED);
    }
}
