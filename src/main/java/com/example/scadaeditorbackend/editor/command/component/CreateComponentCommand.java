package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.dto.ComponentCreateDto;
import com.example.scadaeditorbackend.editor.dto.ComponentResponseDto;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class CreateComponentCommand implements Command<List<ComponentResponseDto>> {

    private final ComponentRepository repository;
    private final List<ComponentCreateDto> dtos;
    private final ObjectMapper mapper;

    @Override
    public CommandResult<List<ComponentResponseDto>> execute() {

        List<Component> createdRoots = dtos.stream()
                .map(this::mapRootComponent)
                .toList();

        List<Component> savedComponents = repository.saveAll(createdRoots);

        List<ComponentResponseDto> response = savedComponents.stream()
                .map(this::toDto)
                .toList();

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
    private ComponentResponseDto toDto(Component entity) {

        ComponentResponseDto dto = new ComponentResponseDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setType(entity.getType());
        dto.setVersion(entity.getVersion());
        dto.setImage(entity.getImage());

        if (entity.getParent() != null) {
            dto.setParent_id(entity.getParent().getId());
        }

        List<ComponentResponseDto> children = entity.getChildren()
                .stream()
                .map(this::toDto)
                .toList();

        dto.setChildren(children);

        return dto;
    }
    /**
     * Маппинг root компоненты.
     * У неё parent_id уже известен.
     */
    private Component mapRootComponent(ComponentCreateDto dto) {

        Component parent = null;

        if (dto.getParent_id() != null) {
            parent = repository.findById(dto.getParent_id())
                    .orElseThrow(() -> new IllegalStateException(
                            "Parent not found: " + dto.getParent_id()
                    ));
        }

        return mapRecursive(dto, parent);
    }

    private Component mapRecursive(ComponentCreateDto dto, Component parent) {

        Component entity = new Component();
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setVersion(dto.getVersion());
        entity.setImage(dto.getImage());
        entity.setParent(parent);

        List<Component> children = dto.getChildren()
                .stream()
                .map(childDto -> mapRecursive(childDto, entity))
                .toList();

        entity.setChildren(children);

        return entity;
    }

    /**
     * Разворачиваем дерево в плоский список
     * чтобы собрать id для undo
     */
    private java.util.stream.Stream<Component> flatten(Component component) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(component),
                component.getChildren().stream().flatMap(this::flatten)
        );
    }
}