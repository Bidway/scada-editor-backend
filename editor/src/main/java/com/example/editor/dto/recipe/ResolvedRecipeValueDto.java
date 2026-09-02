package com.example.editor.dto.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Значение набора, дополненное данными строки таблицы, — то, что нужно рантайму для применения.
 * {@code tag_id} равен {@code null} у локальной строки: такое значение не пишется в ПЛК, а
 * кладётся в состояние свойства в сессии мониторинга. {@code value_type} нужен для коэрсинга
 * перед записью.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResolvedRecipeValueDto {
    private String property_name;
    private String value;
    private String value_type;
    private String tag_id;
}
