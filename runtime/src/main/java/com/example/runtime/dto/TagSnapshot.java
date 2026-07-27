package com.example.runtime.dto;

/** Текущее значение тега строки таблицы (снимок по требованию перед загрузкой рецепта). */
public record TagSnapshot(String tagId, String value) {
}
