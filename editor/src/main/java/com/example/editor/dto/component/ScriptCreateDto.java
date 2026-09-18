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
    /**
     * Дефолт {@code false} совпадает с дефолтом сущности {@code Script}. Без него клиент, не
     * приславший поле, даёт {@code null}, а снимок версии хранит {@code false} — слияние сцены
     * считало такой скрипт изменённым и отвечало ложным {@code merge_conflict}.
     */
    private Boolean displayed = false;
}
