package com.example.channel.command.undo;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateParamUndoHandler implements UndoHandler {

    private final ParamRepository paramRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "UPDATE_PARAM".equals(commandType);
    }

    @Override
    public CommandResult undo(CommandLog log, String userName) {
        NodeParam before = mapper.convertValue(log.getUndoPayload(), NodeParam.class);
        paramRepo.save(before);
        return new CommandResult<>(userName, "NodeParam", log.getEntityId(), "UNDO_UPDATE_PARAM",
                log.getBatchId(), log.getSequence(), null, null, null);
    }
}
