package com.example.editor.dto.release;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/** Ответ на выпуск. {@code unchanged} — дерево не менялось, возвращён прежний выпуск. */
public record ProjectReleaseDto(
        @JsonProperty("version_no") Integer versionNo,
        @JsonProperty("created_at") LocalDateTime createdAt,
        String comment,
        boolean unchanged) {
}
