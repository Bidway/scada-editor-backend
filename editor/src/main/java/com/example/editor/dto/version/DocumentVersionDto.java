package com.example.editor.dto.version;

import com.example.editor.model.version.VersionKind;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Строка списка версий. Содержимого здесь нет намеренно: история сцены — это десятки снимков
 * по несколько сотен килобайт, и отдавать их разом незачем.
 *
 * @param restoredFrom для {@code kind = RESTORE} — номер восстановленной версии, иначе null
 */
public record DocumentVersionDto(
        @JsonProperty("version_no") Integer versionNo,
        VersionKind kind,
        @JsonProperty("user_name") String userName,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("restored_from") Integer restoredFrom) {
}
