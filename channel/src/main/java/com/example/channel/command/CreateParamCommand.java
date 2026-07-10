package com.example.channel.command;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandBatch;
import com.example.channel.config.command.CommandResult;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class CreateParamCommand implements Command<NodeParam> {

    private final ParamRepository repository;
    private final NodeParam param;
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;

    @Override
    public CommandResult<NodeParam> execute() {
        NodeParam saved = repository.save(param);
        JsonNode payload = mapper.valueToTree(saved);
        JsonNode undoPayload = mapper.valueToTree(Map.of("id", saved.getId()));
        return new CommandResult<>(
                userName, "NodeParam", saved.getId(), "CREATE_PARAM",
                batch != null ? batch.getBatchId() : null,
                batch != null ? batch.nextSequence() : null,
                payload, undoPayload, saved
        );
    }
}
