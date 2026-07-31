package com.example.editor.model.recipe;

import lombok.experimental.UtilityClass;

/**
 * Известные значения {@link Recipe#getType()}. Список открытый и не валидируется — как и
 * {@code Component.type}: новый вид набора значений не должен требовать правки бэкенда.
 */
@UtilityClass
public class RecipeTypes {

    /** Набор уставок под конкретный продукт. */
    public static final String RECIPE = "recipe";

    /** Параметры станции — настройки установки, а не продукта. */
    public static final String STATION_PARAMS = "station_params";
}
