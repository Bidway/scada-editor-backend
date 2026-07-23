package com.example.editor.dto.component;

import lombok.Data;

@Data
public class EventResponseDto {
    private Long id;
    private String event_type;
    private String script;
}
