package com.example.editor.dto.recipe;

import lombok.Data;

import java.util.List;

/** Один шаг процедуры: действие при входе и условие перехода к следующему. */
@Data
public class RecipeStepDto {
    private String name;
    private List<RecipeStepActionDto> action;
    private String condition_script;
    private Long timeout_ms;
}
