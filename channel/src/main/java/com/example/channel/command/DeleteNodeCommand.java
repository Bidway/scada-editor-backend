package com.example.channel.command;

import com.example.channel.config.command.CommandBatch;
import com.example.channel.model.Node;
import com.example.channel.repository.NodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class DeleteNodeCommand implements Command<Void> {

    private final NodeRepository repository;
    private final Node node;
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;

    @Override
    public CommandResult<Void> execute() {
        JsonNode snapshot = mapper.valueToTree(node);
        repository.delete(node);
        JsonNode payload = mapper.valueToTree(Map.of("id", node.getId()));
        return new CommandResult<>(
                userName, "Node", node.getId(), "DELETE_NODE",
                batch != null ? batch.getBatchId() : null,
                batch != null ? batch.nextSequence() : null,
                payload, snapshot, null
        );
    }
}
