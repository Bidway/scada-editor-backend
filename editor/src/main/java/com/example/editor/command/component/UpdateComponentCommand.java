package com.example.editor.command.component;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentState;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class UpdateComponentCommand implements Command<List<ComponentResponseDto>> {

    private final ComponentRepository repository;
    private final ComponentPropertyRepository propertyRepository;
    private final List<ComponentCreateDto> dtos;
    private final ObjectMapper mapper;
    private final ComponentMapper componentMapper;

    @Override
    public CommandResult<List<ComponentResponseDto>> execute() {

        List<Component> updatedRoots = dtos.stream()
                .map(this::mapRootComponent)
                .toList();

        List<Component> savedComponents = repository.saveAll(updatedRoots);
        List<ComponentResponseDto> response = componentMapper.toDtoList(savedComponents);

        List<Long> allIds = savedComponents.stream()
                .flatMap(this::flatten)
                .map(Component::getId)
                .toList();

        JsonNode payload = mapper.valueToTree(Map.of("ids", allIds));
        JsonNode undoPayload = mapper.valueToTree(Map.of("ids", allIds));

        return new CommandResult<>(
                "david",
                "component",
                1L,
                "UPDATE_COMPONENT",
                payload,
                undoPayload,
                response
        );
    }

    private Component mapRootComponent(ComponentCreateDto dto) {

        Component entity = repository.findById(dto.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Component not found: " + dto.getId()
                ));

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

        // 🔹 обновляем поля
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setVersion(dto.getVersion());
        entity.setParent(parent);

        // 🔥 STATES
        entity.getStates().clear();

        if (dto.getStates() != null) {

            final boolean[] hasDefault = {false};

            List<ComponentState> states = dto.getStates().stream()
                    .map(stateDto -> {

                        if (Boolean.TRUE.equals(stateDto.getIsDefault())) {
                            if (hasDefault[0]) {
                                throw new IllegalStateException("Only one default state allowed");
                            }
                            hasDefault[0] = true;
                        }

                        return ComponentState.builder()
                                .name(stateDto.getName())
                                .image(stateDto.getImage())
                                .isDefault(stateDto.getIsDefault())
                                .component(entity)
                                .build();
                    })
                    .toList();

            entity.getStates().addAll(states);
        }

        // 🔹 дети
        List<Component> children = dto.getChildren()
                .stream()
                .map(childDto -> {

                    Component childEntity;

                    if (childDto.getId() != null) {
                        childEntity = repository.findById(childDto.getId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Child component not found: " + childDto.getId()
                                ));
                    } else {
                        childEntity = new Component();
                    }

                    return mapRecursive(childDto, childEntity, entity);
                })
                .toList();

        entity.getChildren().clear();
        entity.getChildren().addAll(children);

        ComponentScriptBindingApplier.apply(entity, dto, propertyRepository);

        return entity;
    }

    private Stream<Component> flatten(Component component) {
        return Stream.concat(
                Stream.of(component),
                component.getChildren().stream().flatMap(this::flatten)
        );
    }
}
