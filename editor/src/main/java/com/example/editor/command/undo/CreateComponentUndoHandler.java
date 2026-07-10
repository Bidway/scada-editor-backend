package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandResult;
import com.example.editor.config.command.UndoHandler;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CreateComponentUndoHandler implements UndoHandler {

    private final ComponentRepository componentRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_COMPONENT".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        var ids = mapper.convertValue(log.getPayload().get("ids"), List.class);
        ids.forEach(id -> componentRepo.deleteById(((Number) id).longValue()));
        return new CommandResult<>(userName, "component", null, "UNDO_CREATE_COMPONENT",
                null, null, null);
    }
}
