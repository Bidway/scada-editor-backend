package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Набор автоматизации проекта целиком. Одна форма на ответ {@code GET}, снимок в
 * {@code document_version} ({@code version = null}) и сообщение в {@code automation.definitions}
 * ({@code version} — номер версии).
 */
public record AutomationSetDto(
        @JsonProperty("project_id") Long projectId,
        Integer version,
        List<AutomationTaskDto> tasks,
        List<AutomationVariableDto> variables,
        AutomationWatchdogDto watchdog) {
}
