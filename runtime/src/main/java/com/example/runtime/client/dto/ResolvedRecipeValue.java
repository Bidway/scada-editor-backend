package com.example.runtime.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Резолв одного значения набора, как его отдаёт editor
 * {@code GET /api/editor/recipes/{id}/resolved}. {@code valueType} нужен для коэрсинга значения
 * перед записью. Пустой {@code tagId} означает локальную строку: такое значение не уходит в ПЛК,
 * а кладётся в состояние свойства в сессии — строка адресуется по {@code rowName}.
 */
public record ResolvedRecipeValue(
        @JsonProperty("row_name") String rowName,
        @JsonProperty("value") String value,
        @JsonProperty("value_type") String valueType,
        @JsonProperty("tag_id") String tagId) {

    public boolean isLocal() {
        return tagId == null || tagId.isBlank();
    }
}
