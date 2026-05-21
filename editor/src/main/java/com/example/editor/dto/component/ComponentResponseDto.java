package com.example.editor.dto.component;

import com.example.editor.dto.property.PropertyResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class ComponentResponseDto {

    private Long id;
    private String name;
    private String type;
    private Long version;
    private Long parent_id;
    private List<ComponentStateResponseDto> states;
    private List<ComponentResponseDto> children;
    private List<PropertyResponseDto> properties;
    private List<ScriptResponseDto> scripts;
    private List<BindingResponseDto> bindings;
}
