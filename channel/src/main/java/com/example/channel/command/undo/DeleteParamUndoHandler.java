package com.example.channel.command.undo;

import com.example.channel.config.command.CommandLog;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteParamUndoHandler implements UndoHandler<CommandLog> {

    private final ParamRepository paramRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "DELETE_PARAM".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        NodeParam restored = mapper.convertValue(log.getUndoPayload(), NodeParam.class);
        restored.setId(null);
        paramRepo.save(restored);
        return new CommandResult<>(userName, "NodeParam", log.getEntityId(), "UNDO_DELETE_PARAM",
                log.getBatchId(), log.getSequence(), null, null, null);
    }
}
