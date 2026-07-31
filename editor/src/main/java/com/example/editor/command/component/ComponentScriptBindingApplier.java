package com.example.editor.command.component;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
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

    /**
     * Массовая синхронизация свойств компонента (строки таблицы и т.п.) из DTO.
     * <p>
     * В отличие от scripts/bindings/events чистим <b>только если поле прислано</b>
     * ({@code properties != null}): при {@code null} существующие свойства не трогаем, чтобы не
     * ломать их ведение через отдельный {@code ComponentPropertyController}. Прислали список —
     * полная замена (wholesale), поэтому обновление таблицы должно нести все строки сразу.
     * <p>
     * Имена приводятся {@code trim}'ом и обязаны быть уникальными в пределах компонента: имя —
     * ключ, по которому значения наборов (рецепты, параметры станции) находят свою строку, а
     * {@code writeTag} в скриптах адресует свойство тоже по имени. Два одноимённых свойства
     * сделали бы обе привязки неоднозначными.
     * <p>
     * {@code position} — номер для представления: если фронт его не прислал, проставляем по
     * позиции в массиве. Полная замена не позволяет отличить переименование строки от замены,
     * поэтому здесь значения наборов не переносятся — для переименования есть точечный
     * {@code ComponentPropertyController} (см. {@code ComponentPropertyServiceImpl.update}).
     */
    public void applyProperties(Component entity, ComponentCreateDto dto) {
        if (dto.getProperties() == null) {
            return;
        }
        entity.getProperties().clear();
        Set<String> seenNames = new HashSet<>();
        int index = 0;
        for (PropertyCreateDto p : dto.getProperties()) {
            if (p.getName() == null || p.getName().isBlank()) {
                throw new IllegalStateException("Property name is required");
            }
            String name = p.getName().trim();
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate property name '" + name + "' in component " + entity.getId()
                                + "; names must be unique within a component");
            }
            if (p.getProperty_type() == null || p.getProperty_type().isBlank()) {
                throw new IllegalStateException("Property property_type is required for '" + name + "'");
            }
            if (p.getValue_type() == null || p.getValue_type().isBlank()) {
                throw new IllegalStateException("Property value_type is required for '" + name + "'");
            }
            entity.getProperties().add(
                    ComponentProperty.builder()
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
