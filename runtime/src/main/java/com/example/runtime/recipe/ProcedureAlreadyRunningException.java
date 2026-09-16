package com.example.runtime.recipe;

import com.example.runtime.dto.ProcedureStatusDto;

/**
 * Повторный запуск рецепта, который уже идёт в этом проекте. Отдельное исключение, а не
 * тихий перезапуск: действия шага 0 применились бы поверх работающей мойки — у «Дезинфекции»
 * это «закрыть всё», то есть остановка подающего насоса и сброс заданий расхода и подогрева.
 * В теле ответа возвращается текущий статус, чтобы оператор увидел, на каком шаге мойка,
 * вместо того чтобы её сбить.
 */
public class ProcedureAlreadyRunningException extends RuntimeException {

    private final transient ProcedureStatusDto status;

    public ProcedureAlreadyRunningException(Long projectId, String recipeId, ProcedureStatusDto status) {
        super("Процедура " + recipeId + " уже выполняется в проекте " + projectId);
        this.status = status;
    }

    public ProcedureStatusDto status() {
        return status;
    }
}
