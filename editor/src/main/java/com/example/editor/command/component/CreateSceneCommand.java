package com.example.editor.command.component;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Map;

@RequiredArgsConstructor
public class CreateSceneCommand implements Command<SceneCreateResponseDto> {

    private final ComponentRepository repository;
    private final SceneCreateDto dto;
    private final ObjectMapper mapper;
    private final ComponentMapper componentMapper;
    private final String userName;

    @Override
    public CommandResult<SceneCreateResponseDto> execute() {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new IllegalStateException("Scene name is required");
        }
        if (dto.getProject_id() == null) {
            throw new IllegalStateException("Scene requires project_id");
        }

        Component project = repository.findById(dto.getProject_id())
                .orElseThrow(() -> new IllegalStateException(
                        "Project not found: " + dto.getProject_id()));
        ComponentHierarchyValidator.requireProjectParent(project);

        Component scene = new Component();
        scene.setName(dto.getName());
        scene.setType(ComponentTypes.SCENE);
        scene.setParent(project);
        scene.setStates(new ArrayList<>());
        scene.setChildren(new ArrayList<>());
        scene.setVersion(1L);

        Component saved = repository.save(scene);
        SceneCreateResponseDto response = componentMapper.toSceneCreateDto(saved);

        JsonNode payload = mapper.valueToTree(Map.of("id", saved.getId()));
        JsonNode undoPayload = mapper.valueToTree(Map.of("id", saved.getId()));

        return new CommandResult<>(
                userName,
                "component",
                saved.getId(),
                "CREATE_SCENE",
                payload,
                undoPayload,
                response
        );
    }
}
