package com.example.channel.command.param;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class UpdateNodeParamUndoHandler implements UndoHandler {

    private final ParamRepository paramRepository;
    private final ObjectMapper mapper;
    private final long userId;

    public UpdateNodeParamUndoHandler(ParamRepository paramRepository, ObjectMapper mapper, long userId) {
        this.paramRepository = paramRepository;
        this.mapper = mapper;
        this.userId = userId;
    }


    @Override
    public boolean supports(String commandType) {
        return "UPDATE_NODEPARAM".equals(commandType);
    }

    @Override
    public CommandResult undo(CommandLog source) {
        String newValue = source.getUndoPayload().get("oldValue").asText();
        String oldValue = source.getPayload().get("newValue").asText();
        Long paramId = source.getEntityId();
        NodeParam nodeParam = paramRepository.findById(paramId)
                .orElseThrow(() -> new IllegalArgumentException("Param not found: " + paramId));
        nodeParam.setValue(newValue);
        paramRepository.save(nodeParam);
        return new CommandResult(
                userId,
                "param",
                paramId,
                "UNDO_UPDATE_NODEPARAM",
                mapper.valueToTree(Map.of("newValue", newValue)),
                mapper.valueToTree(Map.of("oldValue", oldValue)),
                null
        );
    }

}
