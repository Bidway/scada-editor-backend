package com.example.scadaeditorbackend.editor.service.Impl;

import com.example.scadaeditorbackend.config.command.CommandManager;
import com.example.scadaeditorbackend.editor.command.component.CreateComponentCommand;
import com.example.scadaeditorbackend.editor.command.component.CreateSceneCommand;
import com.example.scadaeditorbackend.editor.command.component.DeleteComponentCommand;
import com.example.scadaeditorbackend.editor.command.component.UpdateComponentCommand;
import com.example.scadaeditorbackend.editor.dto.*;
import com.example.scadaeditorbackend.editor.mapper.ComponentMapper;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.repository.ComponentRepository;
import com.example.scadaeditorbackend.editor.service.ComponentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComponentServiceImpl implements ComponentService {

    private final ComponentRepository repository;
    private final ObjectMapper mapper;
    private final CommandManager commandManager;
    private final ComponentMapper componentMapper;

    @Override
    public List<ComponentResponseDto> create(List<ComponentCreateDto> components) {
        CreateComponentCommand command =
                new CreateComponentCommand(repository, components, mapper, componentMapper);
        return commandManager.execute(command);
    }
    @Override
    public SceneCreateResponseDto createScene(SceneCreateDto scene) {
        CreateSceneCommand command =
                new CreateSceneCommand(repository, scene, mapper,componentMapper);
        return commandManager.execute(command);
    }

    @Override
    public List<ScenesResponseDto> getScenes() {

        return componentMapper.toScenesDtoList(repository.findByType("scene"));
    }

    @Override
    public Component update(Long id, Component component) {

        UpdateComponentCommand command =
                new UpdateComponentCommand(repository, id, component);
        return commandManager.execute(command);
    }

    @Override
    public void delete(Long id) {

        DeleteComponentCommand command =
                new DeleteComponentCommand(repository, id);

        command.execute();
    }

    @Override
    public ComponentResponseDto getById(Long id) {
        return componentMapper.toDto(repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Component not found")));
    }

    @Override
    public List<Component> getAll() {
        return repository.findAll();
    }
}
