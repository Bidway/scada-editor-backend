package com.example.runtime.client.dto;

import lombok.Data;

/**
 * Binding не выполняется на бэке runtime — хранится только для того, чтобы
 * целиком уйти во фронтенд вместе с деревом проекта и быть интерпретированным там.
 */
@Data
public class EditorBindingDto {
    private Long id;
    private Long component_property_id;
    private String name;
    private String script;
}
