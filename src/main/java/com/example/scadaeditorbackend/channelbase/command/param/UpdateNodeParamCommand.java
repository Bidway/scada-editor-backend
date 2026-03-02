package com.example.scadaeditorbackend.channelbase.command.param;

import com.example.scadaeditorbackend.config.command.Command;
import com.example.scadaeditorbackend.config.command.CommandResult;
import com.example.scadaeditorbackend.channelbase.dto.KeyValue;
import com.example.scadaeditorbackend.channelbase.dto.WsEvent;
import com.example.scadaeditorbackend.channelbase.model.NodeParam;
import com.example.scadaeditorbackend.channelbase.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

public class UpdateNodeParamCommand implements Command {

    private final ParamRepository paramRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper;
    private final Long userId;
    private final Long paramId;
    private final String newValue;

    public UpdateNodeParamCommand(
            ParamRepository paramRepository,
            SimpMessagingTemplate messagingTemplate, ObjectMapper mapper,
            Long userId,
            Long paramId,
            String newValue) {
        this.paramRepository = paramRepository;
        this.messagingTemplate = messagingTemplate;
        this.mapper = mapper;
        this.userId = userId;
        this.paramId = paramId;
        this.newValue = newValue;
    }


    @Override
    public CommandResult execute() {
        NodeParam nodeParam = paramRepository.findById(paramId)
                .orElseThrow(() -> new IllegalArgumentException("Param not found: " + paramId));
        String oldValue = nodeParam.getValue();
        nodeParam.setValue(newValue);
        paramRepository.save(nodeParam);
        messagingTemplate.convertAndSend(
                "/topic/device-tree/1/1",
                new WsEvent<>("PARAM_UPDATED", new KeyValue(nodeParam.getId(), nodeParam.getValue()))
        );

        return new CommandResult(
                userId,
                "param",
                paramId,
                "UPDATE_NODEPARAM",
                mapper.valueToTree(Map.of("newValue", newValue)),
                mapper.valueToTree(Map.of("oldValue", oldValue)),
                null
        );

    }
}
