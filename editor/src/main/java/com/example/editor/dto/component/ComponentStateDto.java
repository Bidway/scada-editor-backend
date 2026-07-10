package com.example.editor.dto.component;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ComponentStateDto {
    private String name;
    private JsonNode image;
    private Boolean isDefault;
}
