package com.example.channel.command.undo;

import com.example.channel.config.command.CommandLog;
import com.example.channel.command.CommandResult;
import com.example.channel.command.UndoHandler;
import com.example.channel.repository.ParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateParamUndoHandler implements UndoHandler<CommandLog> {

    private final ParamRepository paramRepo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_PARAM".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        paramRepo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "NodeParam", log.getEntityId(), "UNDO_CREATE_PARAM",
                log.getBatchId(), log.getSequence(), null, null, null);
    }
}
