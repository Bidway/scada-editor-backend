package com.example.editor.mapper;

import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.dto.template.TemplateComponentResponseDto;
import com.example.editor.model.TemplateComponent;
import com.example.editor.model.TemplateFacePlate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class TemplateComponentMapper {

    /**
     * 1. Преобразование TemplateComponentCreateDto → TemplateComponent
     *    без установки parent, чтобы сохранить все id
     */
    public List<TemplateComponent> toEntitiesFlat(List<TemplateComponentCreateDto> dtos, TemplateFacePlate template) {
        List<TemplateComponent> flatList = new ArrayList<>();
        mapRecursiveFlat(dtos, template, null, flatList);
        return flatList;
    }

    private void mapRecursiveFlat(List<TemplateComponentCreateDto> dtos,
                                  TemplateFacePlate template,
                                  TemplateComponent parent,
                                  List<TemplateComponent> flatList) {
        for (TemplateComponentCreateDto dto : dtos) {
            TemplateComponent entity = new TemplateComponent();
            entity.setName(dto.getName());
            entity.setType(dto.getType());
            entity.setImage(dto.getImage());
            entity.setTemplate(template);
            entity.setParent(parent); // пока parent может быть null, потом исправим

            flatList.add(entity);

            if (!dto.getChildren().isEmpty()) {
                mapRecursiveFlat(dto.getChildren(), template, entity, flatList);
            }
        }
    }

    /**
     * 2. После сохранения всех компонентов в БД:
     *    Построение дерева TemplateComponentResponseDto
     */
    public TemplateComponentResponseDto toDtoTree(TemplateComponent root) {
        if (root == null) return null;

        TemplateComponentResponseDto dto = new TemplateComponentResponseDto();
        dto.setId(root.getId());
        dto.setName(root.getName());
        dto.setType(root.getType());
        dto.setImage(root.getImage());
        dto.setParent_id(root.getParent() != null ? root.getParent().getId() : null);

        List<TemplateComponentResponseDto> childrenDto = root.getChildren()
                .stream()
                .map(this::toDtoTree)
                .collect(Collectors.toList());

        dto.setChildren(childrenDto);

        return dto;
    }
    public TemplateComponent mapTree(TemplateComponentCreateDto dto, TemplateFacePlate template) {

        TemplateComponent entity = new TemplateComponent();
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setImage(dto.getImage());
        entity.setTemplate(template);

        List<TemplateComponent> children = dto.getChildren().stream()
                .map(childDto -> {
                    TemplateComponent child = mapTree(childDto, template);
                    child.setParent(entity); // 👈 ВАЖНО
                    return child;
                })
                .toList();

        entity.setChildren(children);

        return entity;
    }
}