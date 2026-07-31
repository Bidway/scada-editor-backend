package com.example.editor.dto.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** Создание/обновление набора: имя, вид, id таблицы-компонента и значения по строкам. */
@Data
public class RecipeCreateDto {

    @NotBlank
    private String name;

    /** Вид набора (recipe / station_params / ...); не прислан — recipe. См. RecipeTypes. */
    private String type;

    @NotNull
    private Long component_id;

    private List<RecipeValueDto> values;
}
