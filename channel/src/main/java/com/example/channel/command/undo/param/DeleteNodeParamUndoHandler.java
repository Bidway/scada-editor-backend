package com.example.channel.command.undo.param;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandResult;
import com.example.channel.config.command.UndoHandler;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.ParamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DeleteNodeParamUndoHandler implements UndoHandler {

    private final ObjectMapper mapper;
    private final ParamRepository paramRepository;

    public DeleteNodeParamUndoHandler(
            ObjectMapper mapper,
            ParamRepository paramRepository
    ) {
        this.mapper = mapper;
        this.paramRepository = paramRepository;
    }

    @Override
    public boolean supports(String commandType) {
        return "DELETE_NODEPARAM".equals(commandType);
    }

    @Override
    public CommandResult<Void> undo(CommandLog log, Long userId) {

        JsonNode undo = log.getUndoPayload();

        NodeParam param = mapper.convertValue(undo, NodeParam.class);

        NodeParam savedParam = paramRepository.save(param);

        JsonNode payload = mapper.valueToTree(
                Map.of("param", savedParam)
        );
        JsonNode undoPayload = mapper.valueToTree(
                Map.of("paramId", savedParam.getId())
        );

        return new CommandResult<>(
                userId,
                "NODEPARAM",
                param.getId(),
                "CREATE_NODEPARAM",
                payload,
                undoPayload,
                null
        );
    }
}
