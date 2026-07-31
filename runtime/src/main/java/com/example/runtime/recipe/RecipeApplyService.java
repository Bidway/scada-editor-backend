package com.example.runtime.recipe;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.ResolvedRecipe;
import com.example.runtime.client.dto.ResolvedRecipeValue;
import com.example.runtime.dto.ApplyRecipeResult;
import com.example.runtime.kafka.CommandProducer;
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
     * Общий дедлайн ожидания подтверждений: {@code delivery.timeout.ms} продюсера (5 с)
     * плюс запас. Отправка конвейерная, поэтому от числа строк не зависит.
     */
    private static final long ACK_TIMEOUT_MS = 7000;

    private final EditorClient editorClient;
    private final CommandProducer commandProducer;
    private final RuntimeSessionService sessionService;

    public ApplyRecipeResult apply(Long recipeId, String sessionId) {
        ResolvedRecipe resolved = editorClient.getResolvedRecipe(recipeId);
        if (resolved == null) {
            log.warn("Recipe {} resolved to nothing", recipeId);
            return new ApplyRecipeResult(recipeId, 0, 0, 0, 0, List.of(), List.of());
        }
        List<ResolvedRecipeValue> values = resolved.valuesOrEmpty();
        List<String> unmatched = resolved.unmatchedOrEmpty();

        int localApplied = 0;
        List<String> failedRows = new ArrayList<>();
        List<PendingCommand> pending = new ArrayList<>();
        for (ResolvedRecipeValue value : values) {
            Object coerced;
            try {
                coerced = coerce(value.value(), value.valueType());
            } catch (IllegalArgumentException e) {
                // Негодное значение — это дефект строки набора, а не всего рецепта:
                // остальные уставки применяем, эту помечаем как неудавшуюся.
                log.warn("Recipe {} row '{}': {}", recipeId, value.rowName(), e.getMessage());
                failedRows.add(value.rowName());
                continue;
            }
            if (value.isLocal()) {
                boolean applied = hasSession(sessionId)
                        && sessionService.applyLocalProperty(
                                sessionId, resolved.componentId(), value.rowName(), coerced);
                if (applied) {
                    localApplied++;
                } else {
                    if (!hasSession(sessionId)) {
                        log.warn("Recipe {} has local row '{}' but request carries no sessionId",
                                recipeId, value.rowName());
                    }
                    failedRows.add(value.rowName());
                }
            } else {
                // Команды уходят в брокер конвейером; исход каждой узнаём ниже, когда
                // дождёмся подтверждений, — иначе отчёт оператору был бы выдан раньше,
                // чем команда покинула процесс.
                pending.add(new PendingCommand(
                        value.rowName(), commandProducer.send(value.tagId(), coerced)));
            }
        }

        int sent = 0;
        awaitAcknowledgements(pending, recipeId);
        for (PendingCommand command : pending) {
            if (command.delivered()) {
                sent++;
            } else {
                failedRows.add(command.rowName());
            }
        }

        ApplyRecipeResult result = new ApplyRecipeResult(
                recipeId, values.size(), sent, localApplied, failedRows.size(), failedRows, unmatched);
        log.info("Recipe {} applied: {} command(s) sent, {} local value(s) set, {} failed, {} unmatched row(s)",
                recipeId, sent, localApplied, failedRows.size(), unmatched.size());
        return result;
    }

    private static boolean hasSession(String sessionId) {
        return sessionId != null && !sessionId.isBlank();
    }

    /**
     * Команда, отправленная в брокер, и строка набора, которой она принадлежит.
     * {@link #delivered()} опрашивается уже после общего ожидания, поэтому
     * незавершённый к дедлайну future честно считается недоставленным.
     */
    private record PendingCommand(String rowName, CompletableFuture<Boolean> future) {
        boolean delivered() {
            return Boolean.TRUE.equals(future.getNow(false));
        }
    }

    /**
     * Ждёт подтверждений брокера по всем отправленным командам. Отправка конвейерная,
     * поэтому дедлайн общий, а не на каждую команду: он покрывает {@code delivery.timeout.ms}
     * продюсера с запасом. Исключения не пробрасываем — исход каждой команды разбирается
     * по её future.
     */
    private static void awaitAcknowledgements(List<PendingCommand> pending, Long recipeId) {
        if (pending.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] futures = pending.stream()
                .map(PendingCommand::future)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Recipe {}: broker did not acknowledge all commands within {} ms",
                    recipeId, ACK_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Recipe {}: interrupted while waiting for command acknowledgements", recipeId);
        } catch (ExecutionException e) {
            log.warn("Recipe {}: command acknowledgement failed: {}", recipeId, e.getMessage());
        }
    }

    /**
     * Строку набора приводим к Java-типу по объявленному {@code value_type} строки —
     * {@link CommandProducer} по фактическому типу выведет dataType для драйвера. Непарсящееся
     * под тип значение отдаём строкой (решит драйвер).
     * <p>
     * Исключение — boolean: здесь молча отдать строку нельзя. Дискретный тег — это клапан или
     * пуск/стоп механизма, и {@code Boolean.valueOf} на нераспознанном значении вернул бы
     * {@code false}, то есть <b>противоположную</b> уставку, без единого признака ошибки.
     * Поэтому набор допустимых написаний задан явно, а всё остальное — ошибка строки.
     */
    private Object coerce(String value, String valueType) {
        if (value == null) {
            return null;
        }
        if (valueType == null) {
            return value;
        }
        String raw = value.trim();
        try {
            switch (valueType.trim().toLowerCase()) {
                case "number":
                case "double":
                case "float":
                case "real":
                    return Double.parseDouble(raw);
                case "int":
                case "integer":
                case "long":
                    return Long.parseLong(raw);
                case "bool":
                case "boolean":
                    return parseBoolean(raw);
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * Разбор булевой уставки по явному списку написаний. В отличие от {@link Boolean#valueOf},
     * не превращает всё неизвестное в {@code false}, а сигнализирует об ошибке.
     *
     * @throws IllegalArgumentException если значение не опознано — строка уйдёт в failed,
     *                                  и в ПЛК не будет записано ничего
     */
    private static boolean parseBoolean(String raw) {
        switch (raw.toLowerCase()) {
            case "true":
            case "1":
            case "on":
            case "yes":
            case "да":
                return true;
            case "false":
            case "0":
            case "off":
            case "no":
            case "нет":
                return false;
            default:
                throw new IllegalArgumentException(
                        "Значение '" + raw + "' не является булевым: ожидается true/false, 1/0, on/off, yes/no, да/нет");
        }
    }
}
