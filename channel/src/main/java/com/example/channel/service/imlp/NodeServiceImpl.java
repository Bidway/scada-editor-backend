package com.example.channel.service.imlp;

import com.example.channel.command.CrudCommand;
import com.example.channel.config.command.CommandManager;
import com.example.channel.config.command.CommandResult;
import com.example.channel.dto.nodeDto.*;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.model.template.Template;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import com.example.channel.repository.TemplateRepository;
import com.example.channel.service.NodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NodeServiceImpl implements NodeService {

    private final NodeRepository nodeRepository;
    private final DescriptionRepository descriptionRepository;
    private final ParamRepository paramRepository;
    private final TemplateRepository templateRepository;
    private final NodeMapper nodeMapper;
    private final CommandManager commandManager;
    private final ObjectMapper mapper;

    @Transactional
    public CreateNodeResponse createNode(CreateNodeDto createNodeDTO, Long userId) {

        Node node = nodeMapper.toEntity(createNodeDTO);

        CrudCommand<Node> nodeCommand = new CrudCommand<>(
                userId,
                CrudCommand.Action.CREATE,
                nodeRepository,
                mapper,
                node,
                null,
                Node::getId
        );

        CommandResult<Node> nodeResult = commandManager.execute(nodeCommand);
        Node savedNode = nodeResult.getResult();

        CreateNodeResponse response = new CreateNodeResponse();
        response.setNodeDTO(nodeMapper.toDto(savedNode));

        List<Long> paramIds = templateRepository
                .findByIdWithParams(createNodeDTO.getType())
                .map(t -> t.getTemplateParams()
                        .stream()
                        .map(p -> p.getDescriptionId())
                        .toList())
                .orElse(List.of());

        List<Description> descriptions = descriptionRepository.findAll();

        for (Long paramId : paramIds) {

            NodeParam nodeParam = new NodeParam();
            nodeParam.setIdType(paramId);
            nodeParam.setNode(savedNode);
            nodeParam.setValue("");

            CrudCommand<NodeParam> paramCommand = new CrudCommand<>(
                    userId,
                    CrudCommand.Action.CREATE,
                    paramRepository,
                    mapper,
                    nodeParam,
                    null,
                    NodeParam::getId
            );

            CommandResult<NodeParam> paramResult = commandManager.execute(paramCommand);

            ParamDto dto = nodeMapper.toDto(paramResult.getResult(), descriptions);
            response.getParams().add(dto);
        }

        return response;
    }

    @Transactional
    public void deleteNodeByIdNode(String idNode, Long userId) {

        Node node = nodeRepository.findByIdNode(idNode)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + idNode));

        List<NodeParam> params = paramRepository.findByNode(node)
                .orElse(Collections.emptyList());

        for (NodeParam param : params) {

            CrudCommand<NodeParam> deleteParamCmd = new CrudCommand<>(
                    userId,
                    CrudCommand.Action.DELETE,
                    paramRepository,
                    mapper,
                    param,
                    null,
                    NodeParam::getId
            );

            commandManager.execute(deleteParamCmd);
        }

        CrudCommand<Node> deleteNodeCmd = new CrudCommand<>(
                userId,
                CrudCommand.Action.DELETE,
                nodeRepository,
                mapper,
                node,
                null,
                Node::getId
        );

        commandManager.execute(deleteNodeCmd);
    }

    @Override
    public NodeResponse getFullHierarchy(String rootPath ) {

        NodeResponse response = new NodeResponse();

        List<Node> allNodes = nodeRepository.findByIdNodeStartingWith(rootPath);

        List<String> nodesIds = allNodes.stream()
                .map(Node::getIdNode)
                .toList();

        List<Description> descriptions = descriptionRepository.findAll();

        List<NodeParam> allParams = paramRepository.findParamsByNodeIds(nodesIds);

        allParams.forEach(param -> {
            ParamDto dto = nodeMapper.toDto(param, descriptions);
            response.getParams().add(dto);
        });

        allNodes.forEach(node -> {
            NodeDto dto = nodeMapper.toDto(node);
            response.getNodes().add(dto);
        });

        return response;
    }
    @Override
    public NodeResponse getHierarchy(String rootPath ) {

        NodeResponse response = new NodeResponse();

        List<Node> allNodes = nodeRepository.findDirectChildren(rootPath);

        List<String> nodesIds = allNodes.stream()
                .map(Node::getIdNode)
                .toList();

        List<Description> descriptions = descriptionRepository.findAll();

        List<NodeParam> allParams = paramRepository.findParamsByNodeIds(nodesIds);

        allParams.forEach(param -> {
            ParamDto dto = nodeMapper.toDto(param, descriptions);
            response.getParams().add(dto);
        });

        allNodes.forEach(node -> {
            NodeDto dto = nodeMapper.toDto(node);
            response.getNodes().add(dto);
        });

        return response;
    }

    @Override
    public List<String> getSites() {
        List<Node> nodes = nodeRepository.findRootNodes();
        return nodes.stream().map(Node::getIdNode).toList();
    }

    @Override
    public TemplateResponse getTemplates() {
        TemplateResponse response = new TemplateResponse();
        Map<Long, String> templates = templateRepository.findAll()
                .stream()
                .collect(Collectors.toMap(
                        Template::getId,
                        Template::getName
                ));
        response.setTemplates(templates);
        return response;
    }
}
