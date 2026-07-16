package com.example.runtime.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class EditorStateDto {
    private Long id;
    private Long componentId;
    private String name;
    private JsonNode image;
    private Boolean isDefault;
}
