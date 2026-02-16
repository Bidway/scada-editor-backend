package com.example.scadaeditorbackend.command.param;

import com.example.scadaeditorbackend.command.config.Command;
import com.example.scadaeditorbackend.command.config.CommandResult;
import com.example.scadaeditorbackend.dto.paramDto.CreateParamDto;
import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.mapper.NodeMapper;
import com.example.scadaeditorbackend.model.Description;
import com.example.scadaeditorbackend.model.Node;
import com.example.scadaeditorbackend.model.NodeParam;
import com.example.scadaeditorbackend.repository.DescriptionRepository;
import com.example.scadaeditorbackend.repository.NodeRepository;
import com.example.scadaeditorbackend.repository.ParamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class CreateNodeParamCommand implements Command<ParamDto> {
    private final long userId;
    private final ObjectMapper mapper;
    private final DescriptionRepository descriptionRepository;
    private final NodeRepository nodeRepository;
    private final ParamRepository paramRepository;
    private final CreateParamDto dto;
    private final NodeMapper nodeMapper;

    public CreateNodeParamCommand(long userId, ObjectMapper mapper, DescriptionRepository descriptionRepository, NodeRepository nodeRepository, ParamRepository paramRepository, CreateParamDto dto, NodeMapper nodeMapper) {
        this.userId = userId;
        this.mapper = mapper;
        this.descriptionRepository = descriptionRepository;
        this.nodeRepository = nodeRepository;
        this.paramRepository = paramRepository;
        this.dto = dto;
        this.nodeMapper = nodeMapper;
    }

    @Override
    public CommandResult<ParamDto> execute() {
        Description description = descriptionRepository.findByName(dto.getName());
        Node node = nodeRepository.getNodeByIdNode(dto.getIdNode());
        NodeParam nodeParam = new NodeParam();
        nodeParam.setIdType(description.getId());
        nodeParam.setNode(node);
        nodeParam.setValue(dto.getValue());
        NodeParam savedParam = paramRepository.save(nodeParam);
        ParamDto dto = nodeMapper.toDto(savedParam, description);
        return new CommandResult(
                userId,
                "param",
                savedParam.getId(),
                "CREATE_NODEPARAM",
                mapper.valueToTree(Map.of("id",savedParam.getId(),
                        "nodeId", node.getId(),
                        "typeId", description.getId(),
                        "value", savedParam.getValue())),
                mapper.valueToTree(Map.of("paramId", savedParam.getId())),
                dto
        );
    }
}
