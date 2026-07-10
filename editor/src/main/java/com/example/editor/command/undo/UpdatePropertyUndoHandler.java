package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandResult;
import com.example.editor.config.command.UndoHandler;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdatePropertyUndoHandler implements UndoHandler {

    private final ComponentPropertyRepository propertyRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "UPDATE_PROPERTY".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        ComponentProperty snapshot = mapper.convertValue(log.getUndoPayload(), ComponentProperty.class);
        propertyRepo.save(snapshot);
        return new CommandResult<>(userName, "component_property", log.getEntityId(), "UNDO_UPDATE_PROPERTY",
                null, null, null);
    }
}
