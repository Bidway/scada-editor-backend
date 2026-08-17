package com.example.channel.command;

import com.example.channel.config.command.CommandBatch;
import com.example.channel.model.Node;
import com.example.channel.repository.NodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class CreateNodeCommand implements Command<Node> {

    private final NodeRepository repository;
    private final Node node;
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;

    @Override
    public CommandResult<Node> execute() {
        Node saved = repository.save(node);
        JsonNode payload = mapper.valueToTree(saved);
        JsonNode undoPayload = mapper.valueToTree(Map.of("id", saved.getId()));
        return new CommandResult<>(
                userName, "Node", saved.getId(), "CREATE_NODE",
                batch != null ? batch.getBatchId() : null,
                batch != null ? batch.nextSequence() : null,
                payload, undoPayload, saved
        );
    }
}
