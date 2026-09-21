package com.example.runtime.stream;

/**
 * Событие хода процедурного рецепта — уходит оператору тем же WS-каналом, что
 * обновления тегов/свойств. {@code WRITE_FAILED}/{@code STALLED} — компенсация за то,
 * что запись тега из {@code action} шага fire-and-forget: без этого канала неприменённая
 * команда была бы видна только в логе runtime, не оператору.
 * <p>
 * {@code by} и {@code sessionId} — кто и с какого экрана нажал. Событие уходит всем наблюдателям
 * проекта, и без автора второй оператор видел бы, что шаг перескочил, но не видел бы, кто это
 * сделал. У переходов, которые сделал сам runtime (условие шага, тик), оба поля {@code null}.
 */
public record ProcedureEvent(String recipeId, Integer stepIndex, String stepName, Kind kind, String message,
                             String by, String sessionId) {

    public enum Kind {
        STEP_STARTED, STEP_COMPLETED, WRITE_FAILED, STALLED, COMPLETED, ABORTED, PAUSED, RESUMED
    }
}
