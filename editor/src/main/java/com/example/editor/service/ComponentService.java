package com.example.editor.service;



import com.example.editor.dto.*;
import com.example.editor.model.Component;

import java.util.List;

public interface ComponentService {

    List<ComponentResponseDto> create(List<ComponentCreateDto> component);

    SceneCreateResponseDto createScene(SceneCreateDto scene);

    List<ScenesResponseDto> getScenes();

    List<ComponentResponseDto> update(List<ComponentCreateDto> components);

    void delete(List<Long> ids);

    ComponentResponseDto getById(Long id);

    List<Component> getAll();
}
