package com.example.runtime.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Запрос на применение рецепта. Значения не передаются — рантайм берёт их из editor по recipeId. */
@Data
public class ApplyRecipeRequest {

    @NotNull
    private Long recipeId;

    /** Опционально — для контекста/логов (к какому проекту относится). */
    private Long projectId;
}
