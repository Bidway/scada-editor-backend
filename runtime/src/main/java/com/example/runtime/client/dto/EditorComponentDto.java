package com.example.runtime.client.dto;

import lombok.Data;

import java.util.List;

/**
 * Зеркало editor.dto.component.ComponentResponseDto — полное дерево проекта,
 * как его отдаёт GET /api/editor/components/{id} (children/properties/scripts/bindings
 * приходят вложенными сразу, без дополнительных запросов).
 */
@Data
public class EditorComponentDto {
    private Long id;
    private String name;
    private String type;
    private Long version;
    private Long parent_id;
    private List<EditorStateDto> states;
    private List<EditorComponentDto> children;
    private List<EditorPropertyDto> properties;
    private List<EditorScriptDto> scripts;
    private List<EditorBindingDto> bindings;
}
