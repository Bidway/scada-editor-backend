package com.example.editor.dto.recipe;

import lombok.Data;

/** Запись действия шага: короткое имя тега из манифеста и значение (уже типизировано JSON). */
@Data
public class RecipeStepActionDto {
    private String tag;
    private Object value;
}
