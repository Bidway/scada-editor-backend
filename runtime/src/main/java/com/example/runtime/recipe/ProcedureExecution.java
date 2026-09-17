package com.example.runtime.recipe;

import java.time.Duration;
import java.time.Instant;

/**
 * Состояние одной выполняющейся процедуры. Живёт в памяти проекта и дублируется в
 * {@code runtime.procedure_state}, поэтому переживает и уход оператора, и перезапуск сервиса:
 * после рестарта поднимается через {@code ProcedureExecutionService.restore}.
 */
class ProcedureExecution {

    private final String recipeId;
    private int stepIndex;
    private Instant stepStartedAt;
    private boolean confirmed;
    private boolean completed;
    private boolean stalledNotified;
    /**
     * Определение рецепта, по которому идёт процедура. Читается из editor один раз на запуск:
     * условия пересчитываются на каждое изменение тега и каждый тик, и HTTP-запрос на каждый
     * пересчёт нагружал editor сотнями запросов в секунду. Заодно процедура доходит до конца по
     * той версии рецепта, с которой её запустили, даже если рецепт правят посреди мойки.
     * После перезапуска runtime поле пустое и заполняется при первом обращении.
     */
    private volatile com.example.runtime.client.dto.EditorRecipeDto recipe;

    ProcedureExecution(String recipeId) {
        this.recipeId = recipeId;
        this.stepStartedAt = Instant.now();
    }

    /**
     * Восстановление из сохранённого состояния. Действия шага намеренно НЕ применяются:
     * мойка уже в этом положении, повторная запись дёрнула бы клапаны. Время входа в шаг
     * берётся сохранённое, иначе условия на времени отсчитались бы заново.
     */
    static ProcedureExecution restored(String recipeId, int stepIndex, Instant stepStartedAt, boolean confirmed) {
        ProcedureExecution execution = new ProcedureExecution(recipeId);
        execution.stepIndex = stepIndex;
        execution.stepStartedAt = stepStartedAt;
        execution.confirmed = confirmed;
        return execution;
    }

    com.example.runtime.client.dto.EditorRecipeDto recipe() {
        return recipe;
    }

    void useRecipe(com.example.runtime.client.dto.EditorRecipeDto recipe) {
        this.recipe = recipe;
    }

    Instant stepStartedAt() {
        return stepStartedAt;
    }

    String recipeId() {
        return recipeId;
    }

    int stepIndex() {
        return stepIndex;
    }

    boolean confirmed() {
        return confirmed;
    }

    boolean completed() {
        return completed;
    }

    boolean stalledNotified() {
        return stalledNotified;
    }

    long elapsedMs() {
        return Duration.between(stepStartedAt, Instant.now()).toMillis();
    }

    void confirm() {
        this.confirmed = true;
    }

    void enterStep(int index) {
        this.stepIndex = index;
        this.stepStartedAt = Instant.now();
        this.confirmed = false;
        this.stalledNotified = false;
    }

    void markStalledNotified() {
        this.stalledNotified = true;
    }

    void markCompleted() {
        this.completed = true;
    }
}
