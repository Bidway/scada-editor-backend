package com.example.editor.dto.recipe;

import lombok.Data;

import java.util.List;

@Data
public class RecipeResponseDto {
    private String id;
    private String name;
    private List<RecipeTagDto> tags;
    private List<RecipeStepDto> steps;
}
