package com.example.editor.dto.component;

import lombok.Data;

/**
 * Обработчик события компонента. {@code event_type} — одно из
 * {@link com.example.editor.model.component.EventTypes}.
 */
@Data
public class EventPayloadDto {
    private String event_type;
    private String script;
}
