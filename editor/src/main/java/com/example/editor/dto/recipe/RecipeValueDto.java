package com.example.editor.dto.recipe;

import lombok.Data;

/** Значение рецепта на строку: тег и значение уставки. */
@Data
public class RecipeValueDto {
    private String tag_id;
    private String value;
}
