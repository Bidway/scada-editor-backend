package com.example.editor.service;



import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.component.Component;

import java.util.List;

public interface ComponentService {

    List<ComponentResponseDto> create(List<ComponentCreateDto> component);

    ProjectCreateResponseDto createProject(ProjectCreateDto project);

    List<ProjectsResponseDto> getProjects();

    SceneCreateResponseDto createScene(SceneCreateDto scene);

    List<ScenesResponseDto> getScenes(Long projectId);

    List<ComponentResponseDto> update(List<ComponentCreateDto> components);

    void delete(List<Long> ids);

    ComponentResponseDto getById(Long id);

    List<Component> getAll();
}
