package com.example.channel.command.param;

import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandResult;
import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
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
        Description description = descriptionRepository.findById(dto.getId());
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
