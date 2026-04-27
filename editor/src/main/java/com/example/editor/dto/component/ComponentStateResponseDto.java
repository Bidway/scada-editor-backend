package com.example.editor.dto.component;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ComponentStateResponseDto {
    private Long id;
    private Long componentId;
    private String name;
    private JsonNode image;
    private Boolean isDefault;
}
