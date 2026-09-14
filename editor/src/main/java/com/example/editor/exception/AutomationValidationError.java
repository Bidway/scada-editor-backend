package com.example.editor.exception;

/**
 * Одно нарушение в наборе автоматизации.
 *
 * @param task  имя задачи; {@code null} — нарушение уровня проекта (переменные, watchdog)
 * @param field поле в терминах JSON контракта ({@code period_ms}, {@code outputs}, {@code watchdog.tag})
 */
public record AutomationValidationError(String task, String field, String message) {
}
