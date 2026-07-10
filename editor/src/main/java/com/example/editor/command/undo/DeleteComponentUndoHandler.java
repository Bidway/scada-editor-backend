package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandResult;
import com.example.editor.config.command.UndoHandler;
import com.example.editor.model.component.Component;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class DeleteComponentUndoHandler implements UndoHandler {

    private final ComponentRepository componentRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "DELETE_COMPONENT".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        Component restored = mapper.convertValue(log.getUndoPayload(), Component.class);
        restored.setId(null);
        componentRepo.save(restored);
        return new CommandResult<>(userName, "component", log.getEntityId(), "UNDO_DELETE_COMPONENT",
                null, null, null);
    }
}
