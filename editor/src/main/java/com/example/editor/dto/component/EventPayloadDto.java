package com.example.editor.dto.component;

import lombok.Data;

/**
 * Обработчик события компонента. {@code event_type} — одно из
 * {@link com.example.editor.model.component.EventTypes}.
 */
@Data
public class EventPayloadDto {
    /**
     * id существующей строки. {@code null} означает «сущность новая» — это значение, а не
     * пропуск: у объекта, которого ещё нет в базе, id взяться неоткуда. Прислали id —
     * сопоставляем по нему, и переименование остаётся переименованием.
     */
    private Long id;
    private String event_type;
    private String script;
}
