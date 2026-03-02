package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.dto.ComponentCreateDto;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class CreateComponentCommand implements Command<Component> {

    private final ComponentRepository repository;
    private final ComponentCreateDto dto;
    private final ObjectMapper mapper;

    @Override
    public CommandResult<Component> execute() {

        Component root = mapRecursive(dto, null);

        Component savedComponent = repository.save(root);

        JsonNode payload = mapper.valueToTree(Map.of(
                "id", savedComponent.getId(),
                "version", savedComponent.getVersion()
        ));

        JsonNode undoPayload = mapper.valueToTree(Map.of(
                "id", savedComponent.getId()
        ));

        return new CommandResult<>(
                1L,
                "component",
                savedComponent.getId(),
                "CREATE_COMPONENT",
                payload,
                undoPayload,
                savedComponent
        );
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
}
