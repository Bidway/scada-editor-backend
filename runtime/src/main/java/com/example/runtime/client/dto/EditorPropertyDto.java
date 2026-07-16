package com.example.runtime.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
    private boolean logging;
    private JsonNode onChange;

    /**
     * onChange хранится в editor как JSON (обычно — просто JSON-строка с текстом
     * JS-скрипта). Достаём именно текст скрипта независимо от того, как он туда попал.
     */
    public String extractOnChangeScript() {
        if (onChange == null || onChange.isNull()) {
            return null;
        }
        String text = onChange.isTextual() ? onChange.asText() : onChange.toString();
        return (text == null || text.isBlank()) ? null : text;
    }
}
