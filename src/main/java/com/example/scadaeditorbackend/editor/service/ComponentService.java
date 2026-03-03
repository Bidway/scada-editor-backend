package com.example.scadaeditorbackend.editor.service;



import com.example.scadaeditorbackend.editor.dto.*;
import com.example.scadaeditorbackend.editor.model.Component;

import java.util.List;

public interface ComponentService {

    List<ComponentResponseDto> create(List<ComponentCreateDto> component);

    SceneCreateResponseDto createScene(SceneCreateDto scene);

    List<ScenesResponseDto> getScenes();

    Component update(Long id, Component component);

    void delete(Long id);

    ComponentResponseDto getById(Long id);

    List<Component> getAll();
}
