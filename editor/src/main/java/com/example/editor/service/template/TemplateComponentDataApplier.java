package com.example.editor.service.template;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.model.component.EventTypes;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateComponentBinding;
import com.example.editor.model.template.TemplateComponentEvent;
import com.example.editor.model.template.TemplateComponentProperty;
import com.example.editor.model.template.TemplateComponentState;
import com.example.editor.model.template.TemplateScript;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Наполнение компонента шаблона содержимым присланного DTO.
 * <p>
 * Строки с уже известным именем правятся на месте, а не пересоздаются: у DTO шаблона id нет
 * вовсе (только клиентский {@code key}, в базе он не хранится), поэтому единственный ключ,
 * которым присланное можно связать с существующим, — имя. Тот же приём, что у свойств,
 * скриптов и состояний компонента (см. {@code ComponentScriptBindingApplier}). До scada-eap
 * здесь стоял {@code clear()} + вставка, и каждое сохранение шаблона выдавало новые id.
 * <p>
 * Отсутствующий список означает «стереть всё» — как и раньше; шаблон приходит деревом целиком,
 * и семантики «не прислано, не трогать» у него никогда не было.
 */
@UtilityClass
public class TemplateComponentDataApplier {

    public void apply(TemplateComponent entity, TemplateComponentCreateDto dto) {
        applyScripts(entity, dto);
        applyProperties(entity, dto);
        applyStates(entity, dto);
        applyBindings(entity, dto);
        applyEvents(entity, dto);
    }

    private void applyScripts(TemplateComponent entity, TemplateComponentCreateDto dto) {
        if (dto.getScripts() == null) {
            entity.getScripts().clear();
            return;
        }
        Map<String, TemplateScript> existingByName = new HashMap<>();
        for (TemplateScript existing : entity.getScripts()) {
            existingByName.put(existing.getName(), existing);
        }

        List<TemplateScript> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (ScriptCreateDto s : dto.getScripts()) {
            String name = required(s.getName(), "Script name is required");
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate script name '" + name + "' in template component"
                                + "; names must be unique within a component");
            }
            TemplateScript target = existingByName.get(name);
            if (target == null) {
                target = TemplateScript.builder().component(entity).build();
            }
            target.setName(name);
            target.setScript(s.getScript());
            incoming.add(target);
        }
        replace(entity.getScripts(), incoming, seenNames, TemplateScript::getName, TemplateScript::getId);
    }

    /**
     * Те же правила, что и для свойств компонента ({@code ComponentScriptBindingApplier}):
     * имена без краевых пробелов и уникальные в пределах компонента, номер — по позиции в
     * массиве, если не прислан. Шаблон разворачивается в компонент, поэтому невалидный по
     * этим правилам шаблон дал бы невалидную таблицу.
     */
    private void applyProperties(TemplateComponent entity, TemplateComponentCreateDto dto) {
        if (dto.getProperties() == null) {
            entity.getProperties().clear();
            return;
        }
        Map<String, TemplateComponentProperty> existingByName = new HashMap<>();
        for (TemplateComponentProperty existing : entity.getProperties()) {
            existingByName.put(existing.getName(), existing);
        }

        List<TemplateComponentProperty> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        int index = 0;
        for (PropertyCreateDto p : dto.getProperties()) {
            String name = required(p.getName(), "Property name is required");
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate property name '" + name + "' in template component"
                                + "; names must be unique within a component");
            }
            TemplateComponentProperty target = existingByName.get(name);
            if (target == null) {
                target = TemplateComponentProperty.builder().component(entity).build();
            }
            target.setName(name);
            target.setTagId(p.getTag_id());
            target.setPropertyType(p.getProperty_type());
            target.setDescription(p.getDescription());
            target.setValueType(p.getValue_type());
            target.setDefaultValue(p.getDefault_value());
            target.setPosition(p.getPosition() != null ? p.getPosition() : index);
            target.setLogging(p.isLogging());
            target.setOnChange(p.getOnChange());
            incoming.add(target);
            index++;
        }
        replace(entity.getProperties(), incoming, seenNames,
                TemplateComponentProperty::getName, TemplateComponentProperty::getId);
    }

    private void applyStates(TemplateComponent entity, TemplateComponentCreateDto dto) {
        if (dto.getStates() == null) {
            entity.getStates().clear();
            return;
        }
        Map<String, TemplateComponentState> existingByName = new HashMap<>();
        for (TemplateComponentState existing : entity.getStates()) {
            existingByName.put(existing.getName(), existing);
        }

        List<TemplateComponentState> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (ComponentStateDto s : dto.getStates()) {
            String name = required(s.getName(), "State name is required");
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate state name '" + name + "' in template component"
                                + "; names must be unique within a component");
            }
            TemplateComponentState target = existingByName.get(name);
            if (target == null) {
                target = TemplateComponentState.builder().component(entity).build();
            }
            target.setName(name);
            target.setImage(s.getImage());
            target.setIsDefault(s.getIsDefault());
            incoming.add(target);
        }
        replace(entity.getStates(), incoming, seenNames,
                TemplateComponentState::getName, TemplateComponentState::getId);
    }

    /**
     * Привязка свойства шаблона к скрипту отрисовки — то же, что {@code Binding} у реального
     * компонента (см. {@code ComponentScriptBindingApplier.applyBindings}), но проще: у DTO
     * шаблона id никогда нет, поэтому сопоставление всегда по имени, без отдельной ветки для
     * присланного id. В отличие от боевого биндинга, имя здесь обязано быть уникальным в
     * пределах компонента — тот же приём, что у свойств/скриптов/состояний шаблона, и он же
     * даёт использовать общий {@link #replace}.
     */
    private void applyBindings(TemplateComponent entity, TemplateComponentCreateDto dto) {
        if (dto.getBindings() == null) {
            entity.getBindings().clear();
            return;
        }
        Map<String, TemplateComponentBinding> existingByName = new HashMap<>();
        for (TemplateComponentBinding existing : entity.getBindings()) {
            existingByName.put(existing.getName(), existing);
        }

        List<TemplateComponentBinding> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (BindingPayloadDto b : dto.getBindings()) {
            String name = required(b.getName(), "Binding name is required");
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate binding name '" + name + "' in template component"
                                + "; names must be unique within a component");
            }
            TemplateComponentBinding target = existingByName.get(name);
            if (target == null) {
                target = TemplateComponentBinding.builder().component(entity).build();
            }
            target.setName(name);
            target.setScript(b.getScript());
            target.setComponentProperty(resolveBindingProperty(entity, b));
            incoming.add(target);
        }
        replace(entity.getBindings(), incoming, seenNames,
                TemplateComponentBinding::getName, TemplateComponentBinding::getId);
    }

    /**
     * Свойство ищется среди уже применённых {@code entity.getProperties()} — {@link #apply}
     * зовёт {@link #applyProperties} раньше {@link #applyBindings}, поэтому свойство, заведённое
     * этим же запросом, уже в коллекции. Адрес только по имени: id у шаблонного свойства с
     * фронта не приезжает (см. {@link BindingPayloadDto#getComponent_property_id()} — то поле
     * здесь не используется).
     */
    private TemplateComponentProperty resolveBindingProperty(TemplateComponent entity,
                                                              BindingPayloadDto b) {
        String propertyName = b.getComponent_property_name() == null
                ? null : b.getComponent_property_name().trim();
        if (propertyName == null || propertyName.isEmpty()) {
            throw new IllegalStateException(
                    "Binding requires component_property_name");
        }
        for (TemplateComponentProperty property : entity.getProperties()) {
            if (propertyName.equals(property.getName())) {
                return property;
            }
        }
        throw new IllegalStateException(
                "Binding targets property named '" + propertyName
                        + "', which template component does not have");
    }

    /**
     * Обработчик события шаблонного компонента — то же, что {@code ComponentEvent} у реального,
     * тип события ограничен тем же {@link EventTypes}. Как и у {@link #applyBindings}, id с
     * фронта не бывает — сопоставление всегда по типу события.
     */
    private void applyEvents(TemplateComponent entity, TemplateComponentCreateDto dto) {
        if (dto.getEvents() == null) {
            entity.getEvents().clear();
            return;
        }
        Map<String, TemplateComponentEvent> existingByType = new HashMap<>();
        for (TemplateComponentEvent existing : entity.getEvents()) {
            existingByType.put(existing.getEventType(), existing);
        }

        List<TemplateComponentEvent> incoming = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        for (EventPayloadDto e : dto.getEvents()) {
            if (!EventTypes.isValid(e.getEvent_type())) {
                throw new IllegalStateException(
                        "Unknown event_type: " + e.getEvent_type() + "; allowed: " + EventTypes.ALL);
            }
            if (!seenTypes.add(e.getEvent_type())) {
                throw new IllegalStateException(
                        "Duplicate event_type " + e.getEvent_type() + " in template component");
            }
            if (e.getScript() == null || e.getScript().isBlank()) {
                throw new IllegalStateException(
                        "Event " + e.getEvent_type() + " requires a script; omit the event to remove it");
            }
            TemplateComponentEvent target = existingByType.get(e.getEvent_type());
            if (target == null) {
                target = TemplateComponentEvent.builder().component(entity).build();
            }
            target.setEventType(e.getEvent_type());
            target.setScript(e.getScript());
            incoming.add(target);
        }
        replace(entity.getEvents(), incoming, seenTypes,
                TemplateComponentEvent::getEventType, TemplateComponentEvent::getId);
    }

    /**
     * Сначала убираем выпавшие строки (orphanRemoval их удалит), затем добавляем только новые
     * (id ещё нет): у найденных по имени объект в коллекции тот же самый, править его повторной
     * вставкой нельзя.
     */
    private <T> void replace(List<T> target, List<T> incoming, Set<String> seenNames,
                             Function<T, String> nameOf, Function<T, Long> idOf) {
        target.removeIf(existing -> !seenNames.contains(nameOf.apply(existing)));
        for (T candidate : incoming) {
            if (idOf.apply(candidate) == null) {
                target.add(candidate);
            }
        }
    }

    private String required(String name, String message) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(message);
        }
        return name.trim();
    }
}
