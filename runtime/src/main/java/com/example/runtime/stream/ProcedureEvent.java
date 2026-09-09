package com.example.runtime.stream;

/**
 * Событие хода процедурного рецепта — уходит оператору тем же WS-каналом, что
 * обновления тегов/свойств. {@code WRITE_FAILED}/{@code STALLED} — компенсация за то,
 * что запись тега из {@code action} шага fire-and-forget: без этого канала неприменённая
 * команда была бы видна только в логе runtime, не оператору.
 */
public record ProcedureEvent(String recipeId, Integer stepIndex, String stepName, Kind kind, String message) {

    public enum Kind {
        STEP_STARTED, STEP_COMPLETED, WRITE_FAILED, STALLED, COMPLETED, ABORTED
    }
}
