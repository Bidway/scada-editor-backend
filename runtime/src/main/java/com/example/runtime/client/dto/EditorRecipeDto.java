package com.example.runtime.client.dto;

import lombok.Data;

import java.util.List;

/** Зеркало editor.dto.recipe.RecipeResponseDto — GET /api/editor/recipes/{id}. */
@Data
public class EditorRecipeDto {
    private String id;
    private String name;
    private List<EditorRecipeTagDto> tags;
    private List<EditorRecipeStepDto> steps;
}
