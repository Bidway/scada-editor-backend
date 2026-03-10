package com.example.channel.command.param;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandResult;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class DeleteNodeParamCommand implements Command<Void> {

    private final long userId;
    private final ObjectMapper mapper;
    private final ParamRepository paramRepository;
    private final Long paramId;

    public DeleteNodeParamCommand(
            long userId,
            ObjectMapper mapper,
            ParamRepository paramRepository,
            Long paramId
    ) {
        this.userId = userId;
        this.mapper = mapper;
        this.paramRepository = paramRepository;
        this.paramId = paramId;
    }

    @Override
    public CommandResult<Void> execute() {

        NodeParam param = paramRepository.findById(paramId)
                .orElseThrow();

        JsonNode undoPayload = mapper.valueToTree(param);

        JsonNode payload = mapper.valueToTree(
                Map.of("deletedId", paramId)
        );

        paramRepository.delete(param);

        return new CommandResult<>(
                userId,
                "NODE_PARAM",
                paramId,
                "DELETE_NODE_PARAM",
                payload,
                undoPayload,
                null
        );
    }
}
