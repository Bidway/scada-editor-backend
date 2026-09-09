package com.example.editor.dto.recipe;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** Создание/обновление процедурного рецепта: имя, манифест тегов, упорядоченные шаги. */
@Data
public class RecipeCreateDto {

    @NotBlank
    private String name;

    @Valid
    private List<RecipeTagDto> tags;

    @NotEmpty
    @Valid
    private List<RecipeStepDto> steps;
}
