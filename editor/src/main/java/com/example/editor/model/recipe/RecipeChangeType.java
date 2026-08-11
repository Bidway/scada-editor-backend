package com.example.editor.model.recipe;

/** Вид изменения набора значений. */
public enum RecipeChangeType {

    /** Набор создан. */
    CREATE,

    /** Изменено значение одной строки: заполнены row_name, old_value, new_value. */
    VALUE,

    /** Набор переименован: old_value и new_value несут прежнее и новое имя. */
    RENAME,

    /** Набор удалён. */
    DELETE
}
