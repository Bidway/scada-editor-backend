package com.example.editor.dto.template;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.property.PropertyCreateDto;
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
    private List<ComponentStateDto> states;
    private List<PropertyCreateDto> properties;
    private List<ScriptCreateDto> scripts;
    private List<BindingPayloadDto> bindings;
    private List<EventPayloadDto> events;
}
