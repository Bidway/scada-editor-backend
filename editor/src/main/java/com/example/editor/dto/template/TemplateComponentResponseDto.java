package com.example.editor.dto.template;

import com.example.editor.dto.component.ComponentStateResponseDto;
import com.example.editor.dto.component.ScriptResponseDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TemplateComponentResponseDto {
    private Long id;
    private String type;
    private String name;
    private Long parent_id;
    private List<TemplateComponentResponseDto> children = new ArrayList<>();
    private List<ComponentStateResponseDto> states;
    private List<PropertyResponseDto> properties;
    private List<ScriptResponseDto> scripts;
}
