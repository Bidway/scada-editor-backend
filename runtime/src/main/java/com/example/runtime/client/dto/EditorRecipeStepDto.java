package com.example.runtime.client.dto;

import lombok.Data;

import java.util.List;

/** Зеркало editor.dto.recipe.RecipeStepDto. */
@Data
public class EditorRecipeStepDto {
    private String name;
    private List<EditorRecipeStepActionDto> action;
    private String condition_script;
    private Long timeout_ms;
}
