package com.example.editor.dto.component;

import lombok.Data;


@Data
public class ScriptCreateDto {
    /**
     * id существующей строки. {@code null} означает «сущность новая» — это значение, а не
     * пропуск: у объекта, которого ещё нет в базе, id взяться неоткуда. Прислали id —
     * сопоставляем по нему, и переименование остаётся переименованием.
     */
    private Long id;
    private String name;
    private String script;
}
