package com.example.editor.dto.recipe;

import lombok.Data;

/** Значение набора: имя свойства (ключ) и само значение. */
@Data
public class RecipeValueDto {
    private String property_name;
    private String value;
}
