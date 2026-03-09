package com.example.editor.command.component;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.ComponentCreateDto;
import com.example.editor.dto.ComponentResponseDto;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.Component;
import com.example.editor.repository.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@RequiredArgsConstructor
public class CreateComponentCommand implements Command<List<ComponentResponseDto>> {

    private final ComponentRepository repository;
    private final List<ComponentCreateDto> dtos;
    private final ObjectMapper mapper;
    private final ComponentMapper componentMapper;

    @Override
    public CommandResult<List<ComponentResponseDto>> execute() {

        List<Component> createdRoots = dtos.stream()
                .map(this::mapRootComponent)
                .toList();

        List<Component> savedComponents = repository.saveAll(createdRoots);
        List<ComponentResponseDto> response = componentMapper.toDtoList(savedComponents);

        List<Long> allIds = savedComponents.stream()
                .flatMap(this::flatten)
                .map(Component::getId)
                .toList();

        JsonNode payload = mapper.valueToTree(Map.of("ids", allIds));
        JsonNode undoPayload = mapper.valueToTree(Map.of("ids", allIds));

        return new CommandResult<>(
                1L,
                "component",
                1l,
                "CREATE_COMPONENT",
                payload,
                undoPayload,
                response
        );
    }

    private Component mapRootComponent(ComponentCreateDto dto) {
        Component entity;

        if (dto.getId() != null) {
            // Обновляем существующий компонент
            entity = repository.findById(dto.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Component not found: " + dto.getId()
                    ));
        } else {
            // Создаем новый
            entity = new Component();
        }

        Component parent = null;
        if (dto.getParent_id() != null) {
            parent = repository.findById(dto.getParent_id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Parent not found: " + dto.getParent_id()
                    ));
        }

        return mapRecursive(dto, entity, parent);
    }

    private Component mapRecursive(ComponentCreateDto dto, Component entity, Component parent) {
        // Обновляем поля
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setVersion(dto.getVersion());
        entity.setImage(dto.getImage());
        entity.setParent(parent);

        // Обновляем/создаем детей
        List<Component> children = dto.getChildren()
                .stream()
                .map(childDto -> {
                    Component childEntity;
                    if (childDto.getId() != null) {
                        // Обновляем существующего ребенка
                        childEntity = repository.findById(childDto.getId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Child component not found: " + childDto.getId()
                                ));
                    } else {
                        // Новый ребенок
                        childEntity = new Component();
                    }
                    return mapRecursive(childDto, childEntity, entity);
                })
                .collect(Collectors.toList());;

        entity.getChildren().clear();
        entity.getChildren().addAll(children);

        return entity;
    }

    private Stream<Component> flatten(Component component) {
        return Stream.concat(
                Stream.of(component),
                component.getChildren().stream().flatMap(this::flatten)
        );
    }
}