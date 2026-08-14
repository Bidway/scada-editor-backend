package com.example.editor.dto.component;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ComponentStateDto {
    /**
     * id существующей строки. {@code null} означает «сущность новая» — это значение, а не
     * пропуск: у объекта, которого ещё нет в базе, id взяться неоткуда. Прислали id —
     * сопоставляем по нему, и переименование остаётся переименованием.
     */
    private Long id;
    private String name;
    private JsonNode image;
    private Boolean isDefault;
}
