package com.example.editor.mapper;

import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.dto.template.TemplateComponentResponseDto;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.service.template.TemplateComponentDataApplier;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class TemplateComponentMapper {
    private final StateMapper stateMapper;
    private final TemplateComponentPropertyMapper propertyMapper;
    private final TemplateScriptMapper scriptMapper;
    private final TemplateComponentBindingMapper bindingMapper;
    private final TemplateComponentEventMapper eventMapper;

    /** Построение дерева ответа после сохранения. */
    public TemplateComponentResponseDto toDtoTree(TemplateComponent root) {
        if (root == null) {
            return null;
        }

        TemplateComponentResponseDto dto = new TemplateComponentResponseDto();
        dto.setId(root.getId());
        dto.setName(root.getName());
        dto.setType(root.getType());
        dto.setStates(stateMapper.toDtoList(root.getStates()));
        dto.setProperties(propertyMapper.toDtoList(root.getProperties()));
        dto.setScripts(scriptMapper.toDtoList(root.getScripts()));
        dto.setBindings(bindingMapper.toDtoList(root.getBindings()));
        dto.setEvents(eventMapper.toDtoList(root.getEvents()));
        dto.setParent_id(root.getParent() != null ? root.getParent().getId() : null);

        List<TemplateComponentResponseDto> childrenDto = root.getChildren()
                .stream()
                .map(this::toDtoTree)
                .collect(Collectors.toList());

        dto.setChildren(childrenDto);

        return dto;
    }

    /** Создание шаблона: существующего дерева нет, слияние вырождается в построение. */
    public TemplateComponent mapTree(TemplateComponentCreateDto dto, TemplateFacePlate template) {
        return mergeTree(null, dto, template, null);
    }

    /**
     * Слияние присланного дерева с существующим: компонент с уже известным именем правится на
     * месте вместе со своим поддеревом, выпавший — удаляется (orphanRemoval), новый —
     * добавляется. До scada-eap дерево строилось заново, и каждое сохранение шаблона выдавало
     * новые id всему поддереву — включая свойства, скрипты и состояния.
     * <p>
     * Ключ сопоставления — имя плюс номер среди одноимённых соседей: у
     * {@link TemplateComponentCreateDto} нет id, только клиентский {@code key}, которого нет в
     * базе. Одноимённых детей отвергать нельзя — фронт называет фигуры типовыми именами, и в
     * базе уже лежит шаблон с двумя детьми {@code Element} (circle и line).
     * <p>
     * Порядковый номер нужен только чтобы развести одноимённых: пока их порядок между собой не
     * меняется, id держатся. Цена выбора: переименование компонента неотличимо от «удалили
     * один, добавили другой» — то же ограничение, что у свойств и состояний компонента.
     */
    public TemplateComponent mergeTree(TemplateComponent existing,
                                       TemplateComponentCreateDto dto,
                                       TemplateFacePlate template,
                                       TemplateComponent parent) {
        TemplateComponent entity = existing != null ? existing : new TemplateComponent();
        entity.setName(requireName(dto.getName()));
        entity.setType(dto.getType());
        entity.setTemplate(template);
        entity.setParent(parent);

        TemplateComponentDataApplier.apply(entity, dto);

        Map<String, TemplateComponent> existingByKey = new HashMap<>();
        Map<String, Integer> existingSeen = new HashMap<>();
        for (TemplateComponent child : entity.getChildren()) {
            existingByKey.put(siblingKey(child.getName(), existingSeen), child);
        }

        List<TemplateComponentCreateDto> childDtos =
                dto.getChildren() == null ? List.of() : dto.getChildren();
        List<TemplateComponent> incoming = new ArrayList<>();
        Set<TemplateComponent> matched = new HashSet<>();
        Map<String, Integer> incomingSeen = new HashMap<>();
        for (TemplateComponentCreateDto childDto : childDtos) {
            String name = requireName(childDto.getName());
            TemplateComponent existingChild = existingByKey.get(siblingKey(name, incomingSeen));
            if (existingChild != null) {
                matched.add(existingChild);
            }
            incoming.add(mergeTree(existingChild, childDto, template, entity));
        }

        entity.getChildren().removeIf(child -> !matched.contains(child));
        for (TemplateComponent child : incoming) {
            if (child.getId() == null) {
                entity.getChildren().add(child);
            }
        }

        return entity;
    }

    /** Имя плюс номер среди одноимённых соседей — иначе двух детей {@code Element} не развести. */
    private String siblingKey(String name, Map<String, Integer> seen) {
        int occurrence = seen.merge(name, 1, Integer::sum) - 1;
        return name + "#" + occurrence;
    }

    /** Имя здесь и ключ сопоставления, и обязательная колонка — пустое не пропускаем. */
    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Template component name is required");
        }
        return name.trim();
    }
}
