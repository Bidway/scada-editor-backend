package com.example.editor.command.undo;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandResult;
import com.example.editor.config.command.UndoHandler;
import com.example.editor.model.template.TemplateFacePlate;
import com.example.editor.repository.template.TemplateFacePlateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateTemplateUndoHandler implements UndoHandler {

    private final TemplateFacePlateRepository templateRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "UPDATE_TEMPLATE".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        TemplateFacePlate snapshot = mapper.convertValue(log.getUndoPayload(), TemplateFacePlate.class);
        templateRepo.save(snapshot);
        return new CommandResult<>(userName, "template", log.getEntityId(), "UNDO_UPDATE_TEMPLATE",
                null, null, null);
    }
}
