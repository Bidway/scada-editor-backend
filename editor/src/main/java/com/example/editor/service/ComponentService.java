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

    ComponentSaveResponseDto update(List<ComponentCreateDto> dtos, String userName,
                                    VersionKind kind, Integer basedOnVersion, Long sceneId);

    /** Восстановление версии зовёт сохранение изнутри и сцену задаёт само. */
    default ComponentSaveResponseDto update(List<ComponentCreateDto> dtos, String userName,
                                            VersionKind kind, Integer basedOnVersion) {
        return update(dtos, userName, kind, basedOnVersion, null);
    }

    void delete(List<Long> ids, String userName, VersionKind kind, Integer basedOnVersion);

    /**
     * Оставлена для вызывающих, которым {@code based_on_version} неоткуда взять. Не «прежнее
     * поведение без проверки версии» — {@code basedOnVersion = null} доходит до
     * {@link com.example.editor.service.version.DocumentVersionService#requireBaseVersion} и для
     * любой сцены, у которой версия уже есть, даёт {@code IllegalArgumentException} (400), а не
     * тихий обход гарда.
     */
    default void delete(List<Long> ids, String userName, VersionKind kind) {
        delete(ids, userName, kind, null);
    }

    default ComponentSaveResponseDto create(List<ComponentCreateDto> components, String userName) {
        return create(components, userName, VersionKind.MANUAL, null);
    }

    /** См. {@link #delete(List, String, VersionKind)} — то же самое, {@code kind = MANUAL}. */
    default void delete(List<Long> ids, String userName) {
        delete(ids, userName, VersionKind.MANUAL, null);
    }

    ComponentResponseDto getById(Long id);

    List<ComponentResponseDto> getAll();
}
