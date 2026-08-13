package com.example.editor.service;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.component.ComponentSaveResponseDto;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.version.VersionKind;

import java.util.List;

public interface ComponentService {

    ComponentSaveResponseDto create(List<ComponentCreateDto> components, String userName,
                                    VersionKind kind, Integer basedOnVersion);

    ProjectCreateResponseDto createProject(ProjectCreateDto project, String userName);

    List<ProjectsResponseDto> getProjects();

    SceneCreateResponseDto createScene(SceneCreateDto scene, String userName);

    List<ScenesResponseDto> getScenes(Long projectId);

    ComponentSaveResponseDto update(List<ComponentCreateDto> components, String userName,
                                    VersionKind kind, Integer basedOnVersion);

    void delete(List<Long> ids, String userName, VersionKind kind);

    default ComponentSaveResponseDto create(List<ComponentCreateDto> components, String userName) {
        return create(components, userName, VersionKind.MANUAL, null);
    }

    default ComponentSaveResponseDto update(List<ComponentCreateDto> components, String userName) {
        return update(components, userName, VersionKind.MANUAL, null);
    }

    default void delete(List<Long> ids, String userName) {
        delete(ids, userName, VersionKind.MANUAL);
    }

    ComponentResponseDto getById(Long id);

    List<ComponentResponseDto> getAll();
}
