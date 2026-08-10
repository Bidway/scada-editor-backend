package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import com.example.editor.repository.component.ComponentPropertyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreatePropertyUndoHandler implements UndoHandler<CommandLog> {

    private final ComponentPropertyRepository propertyRepo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_PROPERTY".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        propertyRepo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "component_property", log.getEntityId(), "UNDO_CREATE_PROPERTY",
                null, null, null);
    }
}
