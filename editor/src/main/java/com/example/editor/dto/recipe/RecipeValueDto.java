package com.example.editor.dto.recipe;

import lombok.Data;

/** Значение набора на строку таблицы: имя строки (ключ) и само значение. */
@Data
public class RecipeValueDto {
    private String row_name;
    private String value;
}
