package com.example.editor.service;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;

public interface ComponentPropertyService {

    PropertyResponseDto create(PropertyCreateDto property, String userName);

    PropertyResponseDto update(Long id, PropertyCreateDto property, String userName);

    void delete(Long id, String userName, Integer basedOnVersion);
}
