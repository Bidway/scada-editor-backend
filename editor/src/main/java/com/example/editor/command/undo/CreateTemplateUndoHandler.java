package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateTemplateUndoHandler implements UndoHandler<CommandLog> {

    private final TemplateFacePlateRepository templateRepo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_TEMPLATE".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        templateRepo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "template", log.getEntityId(), "UNDO_CREATE_TEMPLATE",
                null, null, null);
    }
}
