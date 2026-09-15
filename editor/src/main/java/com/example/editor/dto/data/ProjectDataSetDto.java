package com.example.editor.dto.data;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Набор таблиц проекта целиком. Одна форма на ответ {@code GET}, снимок в {@code document_version}
 * ({@code version = null}) и то, что читают {@code runtime} и {@code automation}.
 */
public record ProjectDataSetDto(
        @JsonProperty("project_id") Long projectId,
        Integer version,
        List<ProjectDataTableDto> tables) {
}
