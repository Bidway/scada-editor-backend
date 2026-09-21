package com.example.editor.dto.recipe;

import lombok.Data;

import java.util.List;

@Data
public class RecipeResponseDto {
    private String id;
    private String name;
    private List<RecipeTagDto> tags;
    private List<RecipeStepDto> steps;
    /** Безопасное состояние на паузе процедуры: что runtime пишет, когда мойка встаёт. */
    private List<RecipeStepActionDto> pause_action;
}
