package com.example.editor.dto.project;

import com.example.editor.model.component.Component;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class ProjectCreateResponseDto {
    private Long id;
    private String name;
    private String type;
    private Long parent_id;
    private JsonNode image;
    private List<Component> children;
    private Long version;
}
