package com.example.editor.dto.template;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TemplateComponentCreateDto {
    private String key;
    private String type;
    private String name;
    private String parent_key;
    private List<TemplateComponentCreateDto> children = new ArrayList<>();
    private JsonNode image;
}
