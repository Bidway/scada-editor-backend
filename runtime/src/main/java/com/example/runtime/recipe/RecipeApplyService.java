package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.ResolvedRecipe;
import com.example.runtime.client.dto.ResolvedRecipeValue;
import com.example.runtime.dto.ApplyRecipeResult;
import com.example.runtime.dto.FailedRow;
import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.kafka.CommandProducer;
import com.example.runtime.kafka.ValueCoercion;
import com.example.runtime.session.RuntimeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Применение набора значений (рецепт, параметры станции и т.п.): тянет резолв из editor по
 * {@code recipeId} и раскладывает значения по двум путям.
 * <ul>
 *   <li>Строка с тегом — запись через {@link CommandProducer}, тот же путь, что {@code writeTag}:
 *       топик команд → драйвер → ПЛК.</li>
 *   <li>Строка без тега (локальный параметр) — значение ложится в состояние свойства в сессии
 *       мониторинга и уходит фронту; в ПЛК такой строке писать нечего.</li>
 * </ul>
 * Значения server-authoritative: оператор передаёт только id набора, произвольные уставки с
 * клиента не принимаются.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecipeApplyService {

    /**
     * Общий дедлайн ожидания исходов: таймаут ответа шлюза (по умолчанию 5 с) плюс запас.
     * Отправка конвейерная, поэтому от числа строк не зависит.
     */
    private static final long ACK_TIMEOUT_MS = 7000;

    private final EditorClient editorClient;
    private final CommandProducer commandProducer;
    private final RuntimeSessionService sessionService;

    public ApplyRecipeResult apply(String recipeId, String sessionId) {
        ResolvedRecipe resolved = editorClient.getResolvedRecipe(recipeId);
        if (resolved == null) {
            log.warn("Recipe {} resolved to nothing", recipeId);
            return new ApplyRecipeResult(recipeId, 0, 0, 0, 0, List.of(), List.of(), List.of());
        }
        List<ResolvedRecipeValue> values = resolved.valuesOrEmpty();
        List<String> unmatched = resolved.unmatchedOrEmpty();

        int localApplied = 0;
        List<FailedRow> failures = new ArrayList<>();
        List<PendingCommand> pending = new ArrayList<>();
        for (ResolvedRecipeValue value : values) {
            Object coerced;
            try {
                coerced = ValueCoercion.coerce(value.value(), value.valueType());
            } catch (IllegalArgumentException e) {
                // Негодное значение — это дефект строки набора, а не всего рецепта:
                // остальные уставки применяем, эту помечаем как неудавшуюся.
                log.warn("Recipe {} row '{}': {}", recipeId, value.propertyName(), e.getMessage());
                failures.add(new FailedRow(value.propertyName(), FailedRow.INVALID_VALUE, e.getMessage()));
                continue;
            }
            if (value.isLocal()) {
                boolean applied = hasSession(sessionId)
                        && sessionService.applyLocalProperty(
                                sessionId, resolved.componentId(), value.propertyName(), coerced);
                if (applied) {
                    localApplied++;
                } else if (!hasSession(sessionId)) {
                    log.warn("Recipe {} has local row '{}' but request carries no sessionId",
                            recipeId, value.propertyName());
                    failures.add(new FailedRow(value.propertyName(), FailedRow.NO_SESSION,
                            "Локальная строка требует открытой сессии мониторинга"));
                } else {
                    failures.add(new FailedRow(value.propertyName(), FailedRow.NO_SESSION,
                            "Свойство не найдено в сессии"));
                }
            } else {
                // Команды уходят в брокер конвейером; исход каждой узнаём ниже, когда
                // дождёмся подтверждений, — иначе отчёт оператору был бы выдан раньше,
                // чем команда покинула процесс.
                pending.add(new PendingCommand(
                        value.propertyName(), commandProducer.send(value.tagId(), coerced)));
            }
        }

        int sent = 0;
        awaitAcknowledgements(pending, recipeId);
        for (PendingCommand command : pending) {
            CommandOutcome outcome = command.outcome();
            if (outcome.applied()) {
                sent++;
            } else {
                failures.add(new FailedRow(command.rowName(), outcome.status(), outcome.message()));
            }
        }

        List<String> failedRows = failures.stream().map(FailedRow::rowName).toList();
        ApplyRecipeResult result = new ApplyRecipeResult(
                recipeId, values.size(), sent, localApplied, failures.size(), failedRows, unmatched, failures);
        log.info("Recipe {} applied: {} command(s) confirmed by PLC, {} local value(s) set, "
                        + "{} failed, {} unmatched row(s)",
                recipeId, sent, localApplied, failures.size(), unmatched.size());
        for (FailedRow failure : failures) {
            log.warn("Recipe {} row '{}' not applied: {} — {}",
                    recipeId, failure.rowName(), failure.status(), failure.message());
        }
        return result;
    }

    private static boolean hasSession(String sessionId) {
        return sessionId != null && !sessionId.isBlank();
    }

    /**
     * Отправленная команда и строка набора, которой она принадлежит. {@link #outcome()}
     * опрашивается уже после общего ожидания; future, не успевший завершиться к дедлайну,
     * честно отдаёт «исход неизвестен», а не выдаёт себя за отказ — команда могла и
     * примениться, просто ответ ещё в пути.
     */
    private record PendingCommand(String rowName, CompletableFuture<CommandOutcome> future) {
        CommandOutcome outcome() {
            return future.getNow(CommandOutcome.failure(
                    CommandOutcome.NO_CONFIRMATION, "Ответ шлюза не получен к сроку применения набора"));
        }
    }

    /**
     * Ждёт исходов по всем отправленным командам. Отправка конвейерная, поэтому дедлайн
     * общий, а не на каждую команду. Он должен покрывать таймаут ожидания ответа шлюза
     * ({@code kafka.command-timeout-ms}) с запасом: иначе отчёт соберётся раньше, чем
     * ожидания разрешатся сами, и все команды выглядели бы неподтверждёнными. Исключения
     * не пробрасываем — исход каждой команды разбирается по её future.
     */
    private static void awaitAcknowledgements(List<PendingCommand> pending, String recipeId) {
        if (pending.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] futures = pending.stream()
                .map(PendingCommand::future)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Recipe {}: gateway did not report outcome for all commands within {} ms",
                    recipeId, ACK_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Recipe {}: interrupted while waiting for command outcomes", recipeId);
        } catch (ExecutionException e) {
            log.warn("Recipe {}: command outcome failed: {}", recipeId, e.getMessage());
        }
    }

}
