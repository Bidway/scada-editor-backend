package com.example.editor.command.component;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.model.component.Binding;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentEvent;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.component.EventTypes;
import com.example.editor.model.component.Script;
import com.example.editor.repository.component.ComponentPropertyRepository;
import lombok.experimental.UtilityClass;

import java.util.HashSet;
import java.util.Set;

/**
 * Синхронизирует коллекции scripts/bindings/events с DTO при создании/обновлении дерева
 * компонентов.
 */
@UtilityClass
public class ComponentScriptBindingApplier {

    public void apply(
            Component entity,
            ComponentCreateDto dto,
            ComponentPropertyRepository propertyRepository
    ) {
        entity.getScripts().clear();
        if (dto.getScripts() != null) {
            for (ScriptCreateDto s : dto.getScripts()) {
                if (s.getName() == null || s.getName().isBlank()) {
                    throw new IllegalStateException("Script name is required");
                }
                entity.getScripts().add(
                        Script.builder()
                                .name(s.getName())
                                .script(s.getScript())
                                .component(entity)
                                .build()
                );
            }
        }

        entity.getBindings().clear();
        if (dto.getBindings() != null) {
            if (!dto.getBindings().isEmpty() && entity.getId() == null) {
                throw new IllegalStateException(
                        "Bindings require a persisted component id; create the component and its properties first");
            }
            for (BindingPayloadDto b : dto.getBindings()) {
                if (b.getComponent_property_id() == null) {
                    throw new IllegalStateException("Binding requires component_property_id");
                }
                ComponentProperty prop = propertyRepository.findById(b.getComponent_property_id())
                        .orElseThrow(() -> new IllegalStateException(
                                "Component property not found: " + b.getComponent_property_id()));
                if (!prop.getComponent().getId().equals(entity.getId())) {
                    throw new IllegalStateException(
                            "Binding targets property " + b.getComponent_property_id()
                                    + " which does not belong to component " + entity.getId());
                }
                entity.getBindings().add(
                        Binding.builder()
                                .name(b.getName())
                                .script(b.getScript())
                                .component(entity)
                                .componentProperty(prop)
                                .build()
                );
            }
        }

        entity.getEvents().clear();
        if (dto.getEvents() != null) {
            Set<String> seenTypes = new HashSet<>();
            for (EventPayloadDto e : dto.getEvents()) {
                if (!EventTypes.isValid(e.getEvent_type())) {
                    throw new IllegalStateException(
                            "Unknown event_type: " + e.getEvent_type() + "; allowed: " + EventTypes.ALL);
                }
                if (!seenTypes.add(e.getEvent_type())) {
                    throw new IllegalStateException(
                            "Duplicate event_type " + e.getEvent_type() + " for component " + entity.getId());
                }
                if (e.getScript() == null || e.getScript().isBlank()) {
                    throw new IllegalStateException(
                            "Event " + e.getEvent_type() + " requires a script; omit the event to remove it");
                }
                entity.getEvents().add(
                        ComponentEvent.builder()
                                .eventType(e.getEvent_type())
                                .script(e.getScript())
                                .component(entity)
                                .build()
                );
            }
        }
    }
}
