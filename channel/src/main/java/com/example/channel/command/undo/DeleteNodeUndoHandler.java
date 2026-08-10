package com.example.channel.command.undo;

import com.example.channel.config.command.CommandLog;
import com.example.shared.command.CommandResult;
import com.example.shared.command.UndoHandler;
import com.example.channel.model.Node;
import com.example.channel.repository.NodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteNodeUndoHandler implements UndoHandler<CommandLog> {

    private final NodeRepository nodeRepo;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(String commandType) {
        return "DELETE_NODE".equals(commandType);
    }

    @Override
    public CommandResult<?> undo(CommandLog log, String userName) {
        Node restored = mapper.convertValue(log.getUndoPayload(), Node.class);
        restored.setId(null);
        nodeRepo.save(restored);
        return new CommandResult<>(userName, "Node", log.getEntityId(), "UNDO_DELETE_NODE",
                log.getBatchId(), log.getSequence(), null, null, null);
    }
}
