package com.example.editor.dto.component;

import com.example.editor.dto.property.PropertyCreateDto;
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
    // Строки таблицы (и любые свойства компонента) массово. Без дефолта: null = «не прислано»
    // => существующие свойства не трогаем (их можно вести и через ComponentPropertyController).
    private List<PropertyCreateDto> properties;
}


