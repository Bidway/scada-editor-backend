package com.example.editor.dto.scene;

import com.example.editor.model.Component;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class SceneCreateResponseDto {

    private Long id;
    private String name;
    private String type;
    private String parent_key;
    private JsonNode image;
    private List<Component> children;
    private Long version;
}
