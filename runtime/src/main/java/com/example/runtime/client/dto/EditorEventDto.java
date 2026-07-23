package com.example.runtime.client.dto;

import lombok.Data;

/**
 * Обработчик события компонента (onClick и т.п.). На бэке runtime не исполняется —
 * как и Binding, хранится только для того, чтобы уйти во фронтенд вместе с деревом
 * проекта и быть интерпретированным там. Если обработчик пишет тег, он делает это
 * через runScript(...) → ACTION → серверный Script.
 */
@Data
public class EditorEventDto {
    private Long id;
    private String event_type;
    private String script;
}
