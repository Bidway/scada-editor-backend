package com.example.editor.dto.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Резолв набора для применения рантаймом. {@code component_id} нужен рантайму, чтобы найти
 * строки таблицы по имени в индексе сессии; {@code unmatched_rows} — имена строк, которых в
 * таблице больше нет (строку удалили или переименовали в обход точечного API). Их значения
 * применить не к чему, и молча терять их нельзя — иначе пропажа уставки видна только по
 * поведению установки.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResolvedRecipeDto {
    private Long recipe_id;
    private Long component_id;
    private List<ResolvedRecipeValueDto> values;
    private List<String> unmatched_rows;
}
