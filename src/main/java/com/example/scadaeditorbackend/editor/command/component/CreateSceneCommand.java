package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.dto.SceneCreateDto;
import com.example.scadaeditorbackend.editor.dto.SceneCreateResponseDto;
import com.example.scadaeditorbackend.editor.mapper.ComponentMapper;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class CreateSceneCommand implements Command<SceneCreateResponseDto> {

    private final ComponentRepository repository;
    private final SceneCreateDto dto;
    private final ObjectMapper mapper;
    private final ComponentMapper componentMapper;

    @Override
    public CommandResult<SceneCreateResponseDto> execute() {

        Component scene = new Component();
        scene.setName(dto.getName());
        scene.setType("scene");
        scene.setParent(null);
        scene.setImage(mapper.createArrayNode()); // []
        scene.setChildren(new ArrayList<>());

        scene.setVersion(1L);

        Component saved = repository.save(scene);

        // Используем маппер вместо ручного заполнения DTO
        SceneCreateResponseDto response = componentMapper.toSceneCreateDto(saved);

        JsonNode payload = mapper.valueToTree(Map.of(
                "id", saved.getId()
        ));

        JsonNode undoPayload = mapper.valueToTree(Map.of(
                "id", saved.getId()
        ));

        return new CommandResult<>(
                1l,
                "component",
                saved.getId(),
                "CREATE_SCENE",
                payload,
                undoPayload,
                response
        );
    }
}
