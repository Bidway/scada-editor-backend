package com.example.editor.service.Impl;

import com.example.editor.config.command.CommandManager;
import com.example.editor.command.component.CreateComponentCommand;
import com.example.editor.command.component.CreateProjectCommand;
import com.example.editor.command.component.CreateSceneCommand;
import com.example.editor.command.component.DeleteComponentCommand;
import com.example.editor.command.component.UpdateComponentCommand;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.mapper.ComponentMapper;
import com.example.editor.model.component.Component;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.ComponentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComponentServiceImpl implements ComponentService {

    private final ComponentRepository repository;
    private final ComponentPropertyRepository propertyRepository;
    private final ObjectMapper mapper;
    private final CommandManager commandManager;
    private final ComponentMapper componentMapper;

    @Override
    public List<ComponentResponseDto> create(List<ComponentCreateDto> components) {
        CreateComponentCommand command =
                new CreateComponentCommand(repository, propertyRepository, components, mapper, componentMapper);
        return commandManager.execute(command);
    }
    @Override
    public ProjectCreateResponseDto createProject(ProjectCreateDto project) {
        CreateProjectCommand command =
                new CreateProjectCommand(repository, project, mapper, componentMapper);
        return commandManager.execute(command);
    }

    @Override
    public List<ProjectsResponseDto> getProjects() {
        return componentMapper.toProjectsDtoList(
                repository.findByParentIsNullAndType(ComponentTypes.PROJECT));
    }

    @Override
    public SceneCreateResponseDto createScene(SceneCreateDto scene) {
        CreateSceneCommand command =
                new CreateSceneCommand(repository, scene, mapper, componentMapper);
        return commandManager.execute(command);
    }

    @Override
    public List<ScenesResponseDto> getScenes(Long projectId) {
        if (projectId != null) {
            return componentMapper.toScenesDtoList(
                    repository.findByParentIdAndType(projectId, ComponentTypes.SCENE));
        }
        return componentMapper.toScenesDtoList(repository.findByType(ComponentTypes.SCENE));
    }

    @Override
    public List<ComponentResponseDto> update(List<ComponentCreateDto> components) {

        UpdateComponentCommand command =
                new UpdateComponentCommand(repository, propertyRepository, components, mapper, componentMapper);
        return commandManager.execute(command);
    }

    @Override
    public void delete(List<Long> ids) {

        DeleteComponentCommand command =
                new DeleteComponentCommand(repository, ids);

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
