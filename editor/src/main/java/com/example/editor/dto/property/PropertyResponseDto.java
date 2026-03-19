package com.example.editor.dto.property;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class PropertyResponseDto {
    private Long id;
    private Long component_id;
    private String property_type;
    private String tag_id;
    private String description;
    private String value_type;
    private String default_value;
    private boolean logging;
    private JsonNode onChange;
}
