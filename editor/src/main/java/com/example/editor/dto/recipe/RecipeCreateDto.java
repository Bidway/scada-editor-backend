package com.example.editor.dto.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** Создание/обновление рецепта: имя, id таблицы-компонента и набор значений по строкам. */
@Data
public class RecipeCreateDto {

    @NotBlank
    private String name;

    @NotNull
    private Long component_id;

    private List<RecipeValueDto> values;
}
