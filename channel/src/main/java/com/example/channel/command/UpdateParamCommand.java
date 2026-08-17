package com.example.channel.command;

import com.example.channel.config.command.CommandBatch;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UpdateParamCommand implements Command<NodeParam> {

    private final ParamRepository repository;
    private final NodeParam paramBefore;
    private final NodeParam paramAfter;
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;

    @Override
    public CommandResult<NodeParam> execute() {
        NodeParam saved = repository.save(paramAfter);
        JsonNode payload = mapper.valueToTree(saved);
        JsonNode undoPayload = mapper.valueToTree(paramBefore);
        return new CommandResult<>(
                userName, "NodeParam", saved.getId(), "UPDATE_PARAM",
                batch != null ? batch.getBatchId() : null,
                batch != null ? batch.nextSequence() : null,
                payload, undoPayload, saved
        );
    }
}
