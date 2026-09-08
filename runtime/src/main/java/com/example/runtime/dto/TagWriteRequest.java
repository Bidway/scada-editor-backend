package com.example.runtime.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * Точечная запись в ПЛК из «Опций» компонента в мониторе. Всегда массив — запись одного
 * тега это массив из одного элемента, отдельного эндпоинта под единичный случай нет.
 */
@Data
public class TagWriteRequest {

    @NotEmpty
    @Valid
    private List<TagWriteItem> writes;

    /** Сессия мониторинга — опционально, только для контекста/логов (см. {@code ApplyRecipeRequest}). */
    private String sessionId;

    /** Опционально — для контекста/логов. */
    private Long projectId;
}
