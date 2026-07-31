package com.example.editor.command.template;

import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateComponentProperty;
import com.example.editor.model.template.TemplateScript;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.Set;

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

    /**
     * Те же правила, что и для свойств компонента ({@code ComponentScriptBindingApplier}):
     * имена без краевых пробелов и уникальные в пределах компонента, номер — по позиции в
     * массиве, если не прислан. Шаблон разворачивается в компонент, поэтому невалидный по
     * этим правилам шаблон дал бы невалидную таблицу.
     */
    private void applyProperties(TemplateComponent entity, TemplateComponentCreateDto dto) {
        entity.getProperties().clear();
        if (dto.getProperties() == null) {
            return;
        }
        Set<String> seenNames = new HashSet<>();
        int index = 0;
        for (PropertyCreateDto p : dto.getProperties()) {
            if (p.getName() == null || p.getName().isBlank()) {
                throw new IllegalStateException("Property name is required");
            }
            String name = p.getName().trim();
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate property name '" + name + "' in template component"
                                + "; names must be unique within a component");
            }
            entity.getProperties().add(
                    TemplateComponentProperty.builder()
                            .name(name)
                            .tagId(p.getTag_id())
                            .propertyType(p.getProperty_type())
                            .description(p.getDescription())
                            .valueType(p.getValue_type())
                            .defaultValue(p.getDefault_value())
                            .position(p.getPosition() != null ? p.getPosition() : index)
                            .logging(p.isLogging())
                            .onChange(p.getOnChange())
                            .component(entity)
                            .build()
            );
            index++;
        }
    }
}
