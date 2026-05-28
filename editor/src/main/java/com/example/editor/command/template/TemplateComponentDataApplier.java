package com.example.editor.command.template;

import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateComponentProperty;
import com.example.editor.model.template.TemplateScript;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TemplateComponentDataApplier {

    public void apply(TemplateComponent entity, TemplateComponentCreateDto dto) {
        applyScripts(entity, dto);
        applyProperties(entity, dto);
    }

    private void applyScripts(TemplateComponent entity, TemplateComponentCreateDto dto) {
        entity.getScripts().clear();
        if (dto.getScripts() == null) {
            return;
        }
        for (ScriptCreateDto s : dto.getScripts()) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new IllegalStateException("Script name is required");
            }
            entity.getScripts().add(
                    TemplateScript.builder()
                            .name(s.getName())
                            .script(s.getScript())
                            .component(entity)
                            .build()
            );
        }
    }

    private void applyProperties(TemplateComponent entity, TemplateComponentCreateDto dto) {
        entity.getProperties().clear();
        if (dto.getProperties() == null) {
            return;
        }
        for (PropertyCreateDto p : dto.getProperties()) {
            if (p.getName() == null || p.getName().isBlank()) {
                throw new IllegalStateException("Property name is required");
            }
            entity.getProperties().add(
                    TemplateComponentProperty.builder()
                            .name(p.getName())
                            .tagId(p.getTag_id())
                            .propertyType(p.getProperty_type())
                            .description(p.getDescription())
                            .valueType(p.getValue_type())
                            .defaultValue(p.getDefault_value())
                            .logging(p.isLogging())
                            .onChange(jsonToString(p.getOnChange()))
                            .component(entity)
                            .build()
            );
        }
    }

    private static String jsonToString(JsonNode node) {
        return node != null ? node.toString() : null;
    }
}
