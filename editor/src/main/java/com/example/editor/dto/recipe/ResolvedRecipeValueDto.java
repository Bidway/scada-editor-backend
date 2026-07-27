package com.example.editor.dto.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Резолв значения рецепта для применения рантаймом: тег, значение и тип значения
 * (взят из ComponentProperty таблицы по tag_id — нужен рантайму для коэрсинга перед записью).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResolvedRecipeValueDto {
    private String tag_id;
    private String value;
    private String value_type;
}
