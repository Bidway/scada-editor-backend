package com.example.runtime.recipe;

import java.time.Duration;
import java.time.Instant;

/**
 * Состояние одной выполняющейся процедуры — только в памяти сессии. При перезапуске
 * runtime теряется; восстановление — через {@code ProcedureExecutionService.resumeGuess},
 * не через это состояние.
 */
class ProcedureExecution {

    private final String recipeId;
    private int stepIndex;
    private Instant stepStartedAt;
    private boolean confirmed;
    private boolean completed;
    private boolean stalledNotified;

    ProcedureExecution(String recipeId) {
        this.recipeId = recipeId;
        this.stepStartedAt = Instant.now();
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
