package com.example.editor.mapper;

import com.example.editor.command.template.TemplateComponentDataApplier;
import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.dto.template.TemplateComponentResponseDto;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateFacePlate;
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
     * Ключ сопоставления — имя: у {@link TemplateComponentCreateDto} нет id, только клиентский
     * {@code key}, которого нет в базе. Поэтому одноимённые дети одного родителя отвергаются —
     * иначе сопоставление было бы неоднозначным. Это строже, чем у обычных компонентов, где
     * сопоставлять по имени не нужно: там id присылает фронт.
     * <p>
     * Цена выбора: переименование компонента шаблона неотличимо от «удалили один, добавили
     * другой» — то же ограничение, что у свойств и состояний компонента.
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

        Map<String, TemplateComponent> existingByName = new HashMap<>();
        for (TemplateComponent child : entity.getChildren()) {
            existingByName.put(child.getName(), child);
        }

        List<TemplateComponentCreateDto> childDtos =
                dto.getChildren() == null ? List.of() : dto.getChildren();
        List<TemplateComponent> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (TemplateComponentCreateDto childDto : childDtos) {
            String name = requireName(childDto.getName());
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate child name '" + name + "' in template component '"
                                + entity.getName() + "'; names must be unique among siblings");
            }
            incoming.add(mergeTree(existingByName.get(name), childDto, template, entity));
        }

        entity.getChildren().removeIf(child -> !seenNames.contains(child.getName()));
        for (TemplateComponent child : incoming) {
            if (child.getId() == null) {
                entity.getChildren().add(child);
            }
        }

        return entity;
    }

    /** Имя здесь и ключ сопоставления, и обязательная колонка — пустое не пропускаем. */
    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Template component name is required");
        }
        return name.trim();
    }
}
