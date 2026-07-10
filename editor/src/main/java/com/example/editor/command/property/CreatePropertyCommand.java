package com.example.editor.command.property;

import com.example.editor.config.command.Command;
import com.example.editor.config.command.CommandResult;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.mapper.ComponentPropertyMapper;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class CreatePropertyCommand implements Command<PropertyResponseDto> {

    private final ComponentPropertyRepository repository;
    private final ComponentPropertyMapper mapper;
    private final ObjectMapper objectMapper;
    private final ComponentProperty property;
    private final String userName;

    @Override
    public CommandResult<PropertyResponseDto> execute() {


        ComponentProperty saved = repository.save(property);

        PropertyResponseDto response = mapper.toDto(saved);

        JsonNode payload = objectMapper.valueToTree(
                Map.of("id", saved.getId())
        );

        JsonNode undoPayload = objectMapper.valueToTree(
                Map.of("id", saved.getId())
        );

        return new CommandResult<>(
                userName,
                "component_property",
                saved.getId(),
                "CREATE_PROPERTY",
                payload,
                undoPayload,
                response
        );
    }
}
