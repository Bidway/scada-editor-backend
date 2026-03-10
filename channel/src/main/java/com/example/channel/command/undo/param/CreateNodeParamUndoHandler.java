package com.example.channel.command.undo.param;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CreateNodeParamUndoHandler implements UndoHandler {

    private final ObjectMapper mapper;
    private final ParamRepository paramRepository;

    public CreateNodeParamUndoHandler(
            ObjectMapper mapper,
            ParamRepository paramRepository
    ) {
        this.mapper = mapper;
        this.paramRepository = paramRepository;
    }

    @Override
    public boolean supports(String commandType) {
        return "CREATE_NODEPARAM".equals(commandType);
    }

    @Override
    public CommandResult<Void> undo(CommandLog log, Long userId) {

        JsonNode undo = log.getUndoPayload();

        Long paramId = undo.get("paramId").asLong();

        paramRepository.deleteById(paramId);

        JsonNode payload = mapper.valueToTree(
                Map.of("paramId", paramId)
        );

        JsonNode undoPayload = mapper.valueToTree(
                Map.of("paramId", paramId)
        );

        return new CommandResult<>(
                userId,
                "NODEPARAM",
                paramId,
                "DELETE_NODEPARAM",
                payload,
                undoPayload,
                null
        );
    }
}
