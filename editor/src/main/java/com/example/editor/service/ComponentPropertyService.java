package com.example.editor.service;



import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.model.ComponentProperty;

import java.util.List;

public interface ComponentPropertyService {

    PropertyResponseDto create(PropertyCreateDto property);

    PropertyResponseDto update(Long id, PropertyCreateDto property);

    void delete(Long id);
}
