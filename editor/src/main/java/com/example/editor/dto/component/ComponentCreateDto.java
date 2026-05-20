package com.example.editor.dto.component;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ComponentCreateDto {
    private String key;//
    private Long id;//

    private String name;//

    private List<ComponentCreateDto> children = new ArrayList<>();//

    private Long version;//
    private String type;//

    private String parent_key;//
    private Long parent_id;//

    private List<СomponentStateDto> states;//

    /** Именованные скрипты компонента (onClick, onTimer, init и т.д.). */
    private List<ScriptPayloadDto> scripts;

    /**
     * Привязки свойств к выражениям. {@code component_property_id} должен ссылаться на свойство
     * этого же компонента (создайте свойства до bindings).
     */
    private List<BindingPayloadDto> bindings;
}
