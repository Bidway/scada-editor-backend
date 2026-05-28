package com.example.editor.command.component;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
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
public class CreateProjectCommand implements Command<ProjectCreateResponseDto> {

    private final ComponentRepository repository;
    private final ProjectCreateDto dto;
    private final ObjectMapper mapper;
    private final ComponentMapper componentMapper;

    @Override
    public CommandResult<ProjectCreateResponseDto> execute() {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new IllegalStateException("Project name is required");
        }

        Component project = new Component();
        project.setName(dto.getName());
        project.setType(ComponentTypes.PROJECT);
        project.setParent(null);
        project.setStates(new ArrayList<>());
        project.setChildren(new ArrayList<>());
        project.setVersion(1L);

        Component saved = repository.save(project);
        ProjectCreateResponseDto response = componentMapper.toProjectCreateDto(saved);

        JsonNode payload = mapper.valueToTree(Map.of("id", saved.getId()));
        JsonNode undoPayload = mapper.valueToTree(Map.of("id", saved.getId()));

        return new CommandResult<>(
                "david",
                "component",
                saved.getId(),
                "CREATE_PROJECT",
                payload,
                undoPayload,
                response
        );
    }
}
