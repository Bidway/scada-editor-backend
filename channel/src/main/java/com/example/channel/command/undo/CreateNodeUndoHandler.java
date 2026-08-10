package com.example.channel.command.undo;

import com.example.channel.config.command.CommandLog;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import com.example.channel.repository.NodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CreateNodeUndoHandler implements UndoHandler<CommandLog> {

    private final NodeRepository nodeRepo;

    @Override
    public boolean supports(String commandType) {
        return "CREATE_NODE".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        nodeRepo.deleteById(log.getEntityId());
        return new CommandResult<>(userName, "Node", log.getEntityId(), "UNDO_CREATE_NODE",
                log.getBatchId(), log.getSequence(), null, null, null);
    }
}
