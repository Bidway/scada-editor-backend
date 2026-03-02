package com.example.scadaeditorbackend.editor.command.component;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.editor.dto.SceneCreateDto;
import com.example.scadaeditorbackend.editor.dto.SceneCreateResponseDto;
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

    @Override
    public CommandResult<SceneCreateResponseDto> execute() {

        // 1️⃣ создаём сущность
        Component scene = new Component();
        scene.setName(dto.getName());
        scene.setType("scene");
        scene.setParent(null);
        scene.setImage(mapper.createArrayNode()); // []
        scene.setChildren(new ArrayList<>());

        // version = 1 если у тебя @Version
        scene.setVersion(1L);

        // 2️⃣ сохраняем
        Component saved = repository.save(scene);

        // 3️⃣ формируем response DTO
        SceneCreateResponseDto response = new SceneCreateResponseDto();
        response.setId(saved.getId());
        response.setName(saved.getName());
        response.setType(saved.getType());
        response.setParent_key(null);
        response.setImage(saved.getImage());
        response.setChildren(List.of());
        response.setVersion(saved.getVersion());

        // 4️⃣ payload для логирования
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
