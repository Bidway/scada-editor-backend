package com.example.runtime.client.dto;

import lombok.Data;

/** Зеркало editor.dto.recipe.RecipeTagDto. */
@Data
public class EditorRecipeTagDto {
    private String name;
    private String tag;
    private String value_type;
    private String description;
}
