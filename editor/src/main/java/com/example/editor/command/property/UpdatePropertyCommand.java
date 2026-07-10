package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.mapper.ComponentPropertyMapper;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class UpdatePropertyCommand implements Command<PropertyResponseDto> {

    private final ComponentPropertyRepository repository;
    private final ComponentPropertyMapper mapper;
    private final ObjectMapper objectMapper;
    private final Long id;
    private final PropertyCreateDto dto;
    private final String userName;

    @Override
    public CommandResult<PropertyResponseDto> execute() {

        ComponentProperty entity = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Property not found: " + id
                ));

        // сохраняем старое состояние через DTO
        PropertyResponseDto oldDto = mapper.toDto(entity);
        JsonNode undoPayload = objectMapper.valueToTree(oldDto);

        // обновляем через mapper
        mapper.updateEntity(dto, entity);

        ComponentProperty saved = repository.save(entity);

        PropertyResponseDto response = mapper.toDto(saved);

        JsonNode payload = objectMapper.valueToTree(
                Map.of("id", saved.getId())
        );

        return new CommandResult<>(
                userName,
                "component_property",
                saved.getId(),
                "UPDATE_PROPERTY",
                payload,
                undoPayload,
                response
        );
    }
}
