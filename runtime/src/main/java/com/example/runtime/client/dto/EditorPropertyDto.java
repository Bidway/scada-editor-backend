package com.example.runtime.client.dto;

import lombok.Data;

/**
 * Зеркало editor.dto.property.PropertyResponseDto — то, что отдаёт
 * GET /api/editor/components/{id}.
 */
@Data
public class EditorPropertyDto {
    private Long id;
    private String name;
    private Long component_id;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    private Integer position;
    private boolean logging;
    private String onChange;

    /**
     * onChange хранится и отдаётся editor как сырой JS (тот же формат, что и
     * Script компонента). Пустой/пробельный текст трактуем как «скрипта нет».
     */
    public String extractOnChangeScript() {
        return (onChange == null || onChange.isBlank()) ? null : onChange;
    }
}
