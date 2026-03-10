package com.example.channel.command.undo.node;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.repository.NodeRepository;
import org.springframework.stereotype.Component;

@Component
public class CreateNodeUndoHandler implements UndoHandler {

    private final NodeRepository nodeRepository;

    public CreateNodeUndoHandler(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Override
    public boolean supports(String commandType) {
        return "CREATE_NODE".equals(commandType);
    }

    @Override
    public CommandResult<Void> undo(CommandLog log, Long userId) {

        Long nodeId = log.getUndoPayload().get("nodeId").asLong();

        nodeRepository.deleteById(nodeId);

        return new CommandResult<>(
                userId,
                "NODE",
                nodeId,
                "DELETE_NODE",
                log.getPayload(),
                log.getUndoPayload(),
                null
        );
    }
}
