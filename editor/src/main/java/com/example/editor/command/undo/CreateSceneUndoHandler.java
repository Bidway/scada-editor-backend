package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandResult;
import com.example.editor.config.command.UndoHandler;
import com.example.editor.repository.component.ComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateSceneUndoHandler implements UndoHandler {

    private final ComponentRepository componentRepo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_SCENE".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        componentRepo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "component", log.getEntityId(), "UNDO_CREATE_SCENE",
                null, null, null);
    }
}
