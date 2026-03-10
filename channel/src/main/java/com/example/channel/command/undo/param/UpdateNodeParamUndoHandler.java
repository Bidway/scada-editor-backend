package com.example.channel.command.undo.param;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class UpdateNodeParamUndoHandler implements UndoHandler {

    private final ParamRepository paramRepository;
    private final ObjectMapper mapper;

    public UpdateNodeParamUndoHandler(ParamRepository paramRepository, ObjectMapper mapper) {
        this.paramRepository = paramRepository;
        this.mapper = mapper;
    }


    @Override
    public boolean supports(String commandType) {
        return "UPDATE_NODEPARAM".equals(commandType);
    }

    @Override
    public CommandResult undo(CommandLog source, Long userId) {
        String newValue = source.getUndoPayload().get("oldValue").asText();
        String oldValue = source.getPayload().get("newValue").asText();
        Long paramId = source.getEntityId();
        NodeParam nodeParam = paramRepository.findById(paramId)
                .orElseThrow(() -> new IllegalArgumentException("Param not found: " + paramId));
        nodeParam.setValue(newValue);
        paramRepository.save(nodeParam);
        return new CommandResult(
                userId,
                "NODEPARAM",
                paramId,
                "UPDATE_NODEPARAM",
                mapper.valueToTree(Map.of("newValue", newValue)),
                mapper.valueToTree(Map.of("oldValue", oldValue)),
                null
        );
    }

}
