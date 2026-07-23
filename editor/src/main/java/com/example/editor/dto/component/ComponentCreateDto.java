package com.example.editor.dto.component;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ComponentCreateDto {
    private String key;
    private Long id;
    private String name;
    private List<ComponentCreateDto> children = new ArrayList<>();
    private Long version;
    private String type;
    private String parent_key;
    private Long parent_id;
    private List<ComponentStateDto> states;
    private List<ScriptCreateDto> scripts;
    private List<BindingPayloadDto> bindings;
    private List<EventPayloadDto> events;
}


