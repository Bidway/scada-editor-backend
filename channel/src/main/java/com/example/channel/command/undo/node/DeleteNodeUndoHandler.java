package com.example.channel.command.undo.node;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.model.Node;
import com.example.channel.repository.NodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DeleteNodeUndoHandler implements UndoHandler {

    private final NodeRepository nodeRepository;
    private final ObjectMapper mapper;

    public DeleteNodeUndoHandler(
            NodeRepository nodeRepository,
            ObjectMapper mapper
    ) {
        this.nodeRepository = nodeRepository;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(String commandType) {
        return "DELETE_NODE".equals(commandType);
    }

    @Override
    public CommandResult<Void> undo(CommandLog log, Long userId) {

        Node node = mapper.convertValue(
                log.getUndoPayload(),
                Node.class
        );

        Node saved = nodeRepository.save(node);

        return new CommandResult<>(
                userId,
                "NODE",
                saved.getId(),
                "CREATE_NODE",
                mapper.valueToTree(saved),
                mapper.valueToTree(Map.of("nodeId", saved.getId())),
                null
        );
    }
}
