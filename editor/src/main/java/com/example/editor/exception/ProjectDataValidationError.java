package com.example.editor.exception;

/** Одно нарушение в наборе данных проекта: таблица, поле формы, текст для человека. */
public record ProjectDataValidationError(String table, String field, String message) {
}
