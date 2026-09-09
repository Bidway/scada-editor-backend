package com.example.editor.dto.recipe;

import lombok.Data;

/** Один тег манифеста: короткое имя, путь в проекте, тип для валидации, имя по-русски для UI. */
@Data
public class RecipeTagDto {
    private String name;
    private String tag;
    private String value_type;
    private String description;
}
