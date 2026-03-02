package com.example.scadaeditorbackend.editor.service;



import com.example.scadaeditorbackend.editor.dto.ComponentCreateDto;
import com.example.scadaeditorbackend.editor.dto.ComponentResponseDto;
import com.example.scadaeditorbackend.editor.model.Component;

import java.util.List;

public interface ComponentService {

    List<ComponentResponseDto> create(List<ComponentCreateDto> component);

    Component update(Long id, Component component);

    void delete(Long id);

    Component getById(Long id);

    List<Component> getAll();
}
