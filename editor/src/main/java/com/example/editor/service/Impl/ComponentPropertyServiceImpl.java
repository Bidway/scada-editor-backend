package com.example.editor.service.Impl;

import com.example.editor.config.command.CommandManager;
import com.example.editor.command.property.CreatePropertyCommand;
import com.example.editor.command.property.DeletePropertyCommand;
import com.example.editor.command.property.UpdatePropertyCommand;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.mapper.ComponentPropertyMapper;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.service.ComponentPropertyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ComponentPropertyServiceImpl implements ComponentPropertyService {

    private final ComponentPropertyRepository repository;
    private final CommandManager commandManager;
    private final ComponentPropertyMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public PropertyResponseDto create(PropertyCreateDto dto, String userName) {
        ComponentProperty entity = mapper.toEntity(dto);
        CreatePropertyCommand command =
                new CreatePropertyCommand(repository, mapper, objectMapper, entity, userName);
        return commandManager.execute(command);
    }

    @Override
    public PropertyResponseDto update(Long id, PropertyCreateDto dto, String userName) {
        UpdatePropertyCommand command =
                new UpdatePropertyCommand(repository, mapper, objectMapper, id, dto, userName);
        return commandManager.execute(command);
    }

    @Override
    public void delete(Long id, String userName) {
        DeletePropertyCommand command = new DeletePropertyCommand(repository, id, userName, objectMapper);
        commandManager.execute(command);
    }
}
