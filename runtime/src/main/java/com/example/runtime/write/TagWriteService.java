package com.example.runtime.write;

import com.example.runtime.config.KafkaProperties;
import com.example.runtime.dto.TagWriteItem;
import com.example.runtime.dto.TagWriteRequest;
import com.example.runtime.dto.TagWriteResult;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.ValueCoercion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Точечная запись тегов в ПЛК из «Опций» компонента в мониторе — тот же {@link CommandProducer},
 * что у применения рецепта ({@code RecipeApplyService}) и у {@code writeTag} из скрипта, но
 * адрес тега уже известен (пришёл с фронта как {@code tag_id} свойства), поэтому резолва через
 * индекс сессии не требуется вовсе.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TagWriteService {

    /** Общий дедлайн ожидания исходов — таймаут ответа шлюза плюс запас, как у RecipeApplyService. */
    private static final long ACK_TIMEOUT_MARGIN_MS = 2000;

    private final CommandProducer commandProducer;
    private final KafkaProperties kafkaProperties;

    private record Pending(int index, String tagId, CompletableFuture<CommandOutcome> future) {
    }

    public List<TagWriteResult> write(TagWriteRequest request) {
        List<TagWriteItem> items = request.getWrites();
        TagWriteResult[] results = new TagWriteResult[items.size()];
        List<Pending> pending = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            TagWriteItem item = items.get(i);
            try {
                Object coerced = ValueCoercion.coerce(item.getValue(), item.getValueType());
                pending.add(new Pending(i, item.getTagId(), commandProducer.send(item.getTagId(), coerced)));
            } catch (IllegalArgumentException e) {
                log.warn("Tag write '{}': {}", item.getTagId(), e.getMessage());
                results[i] = new TagWriteResult(item.getTagId(), false, TagWriteResult.INVALID_VALUE, e.getMessage());
            }
        }

        awaitAcknowledgements(pending);
        for (Pending p : pending) {
            CommandOutcome outcome = p.future().getNow(CommandOutcome.failure(
                    CommandOutcome.NO_CONFIRMATION, "Ответ шлюза не получен к сроку записи"));
            results[p.index()] = new TagWriteResult(p.tagId(), outcome.applied(), outcome.status(), outcome.message());
        }

        return Arrays.asList(results);
    }

    /**
     * Ждёт исходов по всем отправленным командам разом — отправка конвейерная, дедлайн общий,
     * не на каждую (см. {@code RecipeApplyService.awaitAcknowledgements}, тот же приём).
     */
    private void awaitAcknowledgements(List<Pending> pending) {
        if (pending.isEmpty()) {
            return;
        }
        long timeoutMs = kafkaProperties.getCommandTimeoutMs() + ACK_TIMEOUT_MARGIN_MS;
        CompletableFuture<?>[] futures = pending.stream()
                .map(Pending::future)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Tag write: gateway did not report outcome for all {} command(s) within {} ms",
                    pending.size(), timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Tag write: interrupted while waiting for command outcomes");
        } catch (ExecutionException e) {
            log.warn("Tag write: command outcome failed: {}", e.getMessage());
        }
    }
}
