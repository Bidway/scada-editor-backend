package com.example.runtime.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Резолв набора значений целиком. {@code componentId} нужен, чтобы найти локальные строки по
 * имени в индексе сессии; {@code unmatchedRows} — строки набора, которых в таблице больше нет
 * (их значения применять не к чему, но и терять молча нельзя — они попадают в отчёт).
 */
public record ResolvedRecipe(
        @JsonProperty("recipe_id") String recipeId,
        @JsonProperty("component_id") Long componentId,
        @JsonProperty("values") List<ResolvedRecipeValue> values,
        @JsonProperty("unmatched_rows") List<String> unmatchedRows) {

    public List<ResolvedRecipeValue> valuesOrEmpty() {
        return values == null ? List.of() : values;
    }

    public List<String> unmatchedOrEmpty() {
        return unmatchedRows == null ? List.of() : unmatchedRows;
    }
}
