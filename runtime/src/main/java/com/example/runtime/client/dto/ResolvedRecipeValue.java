package com.example.runtime.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Резолв значения рецепта, как его отдаёт editor {@code GET /api/editor/recipes/{id}/resolved}.
 * {@code valueType} нужен для коэрсинга значения перед записью в тег.
 */
public record ResolvedRecipeValue(
        @JsonProperty("tag_id") String tagId,
        @JsonProperty("value") String value,
        @JsonProperty("value_type") String valueType) {
}
