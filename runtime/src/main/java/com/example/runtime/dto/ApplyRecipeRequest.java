package com.example.runtime.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Запрос на применение набора. Значения не передаются — рантайм берёт их из editor по recipeId. */
@Data
public class ApplyRecipeRequest {

    @NotBlank
    private String recipeId;

    /**
     * Сессия мониторинга, в которой применяется набор. Нужна только для строк без тега:
     * их значение живёт в состоянии сессии, а не в ПЛК. Без sessionId теговые строки
     * применяются как обычно, а локальные попадают в {@code failedRows} отчёта.
     */
    private String sessionId;

    /** Опционально — для контекста/логов (к какому проекту относится). */
    private Long projectId;
}
