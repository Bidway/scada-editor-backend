package com.example.channel.command.node;

import com.example.channel.command.param.CreateNodeParamCommand;
import com.example.channel.config.command.Command;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.CommandResult;
import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.CreateNodeResponse;
import com.example.channel.dto.paramDto.CreateParamDto;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import com.example.channel.repository.TemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public class CreateNodeCommand implements Command<CreateNodeResponse> {

    private final Long userId;
    private final NodeRepository nodeRepository;
    private final DescriptionRepository descriptionRepository;
    private final TemplateRepository templateRepository;
    private final ParamRepository paramRepository;
    private final NodeMapper nodeMapper;
    private final CommandManager commandManager;
    private final CreateNodeDto dto;
    private final ObjectMapper mapper;

    public CreateNodeCommand(
            Long userId,
            NodeRepository nodeRepository,
            DescriptionRepository descriptionRepository,

            TemplateRepository templateRepository, ParamRepository paramRepository,
            NodeMapper nodeMapper,
            CommandManager commandManager,
            CreateNodeDto dto, ObjectMapper mapper
    ) {
        this.userId = userId;
        this.nodeRepository = nodeRepository;
        this.descriptionRepository = descriptionRepository;
        this.templateRepository = templateRepository;
        this.paramRepository = paramRepository;
        this.nodeMapper = nodeMapper;
        this.commandManager = commandManager;
        this.dto = dto;
        this.mapper = mapper;
    }

    @Override
    public CommandResult<CreateNodeResponse> execute() {

        Node node = nodeMapper.toEntity(dto);
        Node savedNode = nodeRepository.save(node);

//        savedNode.updateIdNode();

        boolean isParent = "cha".equals(dto.getType());

        CreateNodeResponse response = new CreateNodeResponse();
        response.setNodeDTO(nodeMapper.toDto(savedNode));

        List<Long> paramIds =
                templateRepository.findByNameWithParams(dto.getType())
                        .getTemplateParams()
                        .stream()
                        .map(p -> p.getDescriptionId())
                        .toList();

        List<Description> descriptions = descriptionRepository.findAll();

        for (Long paramId : paramIds) {

            CreateParamDto paramDto = new CreateParamDto();
            paramDto.setId(paramId);
            paramDto.setIdNode(savedNode.getIdNode());
            paramDto.setValue("");

            CommandResult<ParamDto> paramResult =
                    commandManager.execute(
                            new CreateNodeParamCommand(
                                    userId,
                                    mapper,
                                    descriptionRepository,
                                    nodeRepository,
                                    paramRepository,
                                    paramDto,
                                    nodeMapper
                            )
                    );

            response.getParams().add(paramResult.getResult());
        }

        return new CommandResult<>(
                userId,
                "NODE",
                savedNode.getId(),
                "CREATE_NODE",
                mapper.valueToTree(savedNode),
                mapper.valueToTree(Map.of("nodeId", savedNode.getId())),
                response
        );
    }
}
