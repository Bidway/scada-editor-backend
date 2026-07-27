package com.example.runtime.dto;

import java.util.List;

/** Отчёт о применении рецепта: сколько уставок отправлено в теги и какие не удалось. */
public record ApplyRecipeResult(Long recipeId, int total, int sent, int failed, List<String> failedTags) {
}
