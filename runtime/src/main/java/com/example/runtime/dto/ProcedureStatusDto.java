package com.example.runtime.dto;

/** Отчёт о текущем состоянии выполняющейся процедуры — для первичной отрисовки экрана. */
public record ProcedureStatusDto(String recipeId, int stepIndex, String stepName,
                                 long elapsedMs, boolean confirmed, boolean completed, boolean stalled) {
}
