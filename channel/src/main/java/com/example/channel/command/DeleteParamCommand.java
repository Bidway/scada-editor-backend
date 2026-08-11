package com.example.channel.command;

import com.example.shared.command.Command;
import com.example.channel.config.command.CommandBatch;
import com.example.shared.command.CommandResult;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class DeleteParamCommand implements Command<Void> {

    private final ParamRepository repository;
    private final NodeParam param;
    private final ObjectMapper mapper;
    private final String userName;
    private final CommandBatch batch;

    @Override
    public CommandResult<Void> execute() {
        JsonNode snapshot = mapper.valueToTree(param);
        repository.delete(param);
        JsonNode payload = mapper.valueToTree(Map.of("id", param.getId()));
        return new CommandResult<>(
                userName, "NodeParam", param.getId(), "DELETE_PARAM",
                batch != null ? batch.getBatchId() : null,
                batch != null ? batch.nextSequence() : null,
                payload, snapshot, null
        );
    }
}
