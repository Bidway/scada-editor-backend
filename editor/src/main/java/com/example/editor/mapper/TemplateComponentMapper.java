package com.example.editor.mapper;

import com.example.editor.command.template.TemplateComponentDataApplier;
import com.example.editor.dto.template.TemplateComponentCreateDto;
import com.example.editor.dto.template.TemplateComponentResponseDto;
import com.example.editor.model.template.TemplateComponent;
import com.example.editor.model.template.TemplateComponentState;
import com.example.editor.model.template.TemplateFacePlate;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class TemplateComponentMapper {
    private final StateMapper stateMapper;
    private final TemplateComponentPropertyMapper propertyMapper;
    private final TemplateScriptMapper scriptMapper;

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
            TemplateComponent entity = mapComponentFields(dto, template, parent);
            flatList.add(entity);

            if (dto.getChildren() != null && !dto.getChildren().isEmpty()) {
                mapRecursiveFlat(dto.getChildren(), template, entity, flatList);
            }
        }
    }

    /**
     * 2. После сохранения всех компонентов в БД:
     *    Построение дерева TemplateComponentResponseDto
     */
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

    public TemplateComponent mapTree(TemplateComponentCreateDto dto, TemplateFacePlate template) {
        TemplateComponent entity = mapComponentFields(dto, template, null);

        if (dto.getChildren() != null) {
            List<TemplateComponent> children = dto.getChildren().stream()
                    .map(childDto -> {
                        TemplateComponent child = mapTree(childDto, template);
                        child.setParent(entity);
                        return child;
                    })
                    .toList();
            entity.setChildren(new ArrayList<>(children));
        }

        return entity;
    }

    private TemplateComponent mapComponentFields(
            TemplateComponentCreateDto dto,
            TemplateFacePlate template,
            TemplateComponent parent
    ) {
        TemplateComponent entity = new TemplateComponent();
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setTemplate(template);
        entity.setParent(parent);

        entity.getStates().clear();
        if (dto.getStates() != null) {
            List<TemplateComponentState> states = dto.getStates().stream()
                    .map(state -> TemplateComponentState.builder()
                            .name(state.getName())
                            .image(state.getImage())
                            .isDefault(state.getIsDefault())
                            .component(entity)
                            .build())
                    .toList();
            entity.setStates(new ArrayList<>(states));
        }

        TemplateComponentDataApplier.apply(entity, dto);

        return entity;
    }
}
