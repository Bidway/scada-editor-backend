package com.example.channel.service.imlp;

import com.example.channel.command.CreateNodeCommand;
import com.example.channel.command.CreateParamCommand;
import com.example.channel.command.DeleteNodeCommand;
import com.example.channel.command.DeleteParamCommand;
import com.example.channel.config.command.CommandBatch;
import com.example.channel.config.command.CommandManager;
import com.example.channel.dto.KeyValue;
import com.example.channel.dto.nodeDto.*;
import com.example.channel.dto.paramDto.ParamDto;
import com.example.channel.mapper.NodeMapper;
import com.example.channel.model.Description;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import com.example.channel.repository.TemplateRepository;
import com.example.channel.service.NodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    public CreateNodeResponse createNode(CreateNodeDto createNodeDTO, String userName) {

        Node node = nodeMapper.toEntity(createNodeDTO);
        CommandBatch batch = CommandBatch.start();

        Node savedNode = commandManager.execute(
                new CreateNodeCommand(nodeRepository, node, mapper, userName, batch)
        ).getResult();

        CreateNodeResponse response = new CreateNodeResponse();
        response.setBatchId(batch.getBatchId());
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
            nodeParam.setIdNode(savedNode.getIdNode());
            nodeParam.setValue("");

            NodeParam savedParam = commandManager.execute(
                    new CreateParamCommand(paramRepository, nodeParam, mapper, userName, batch)
            ).getResult();

            ParamDto dto = nodeMapper.toDto(savedParam, descriptions);
            response.getParams().add(dto);
        }

        return response;
    }

    @Transactional
    public UUID deleteNodeByIdNode(String idNode, String userName) {

        Node node = nodeRepository.findByIdNode(idNode)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + idNode));

        List<NodeParam> params = paramRepository.findByIdNode(node.getIdNode())
                .orElse(Collections.emptyList());

        CommandBatch batch = CommandBatch.start();

        for (NodeParam param : params) {
            commandManager.execute(new DeleteParamCommand(paramRepository, param, mapper, userName, batch));
        }

        commandManager.execute(new DeleteNodeCommand(nodeRepository, node, mapper, userName, batch));

        return batch.getBatchId();
    }

    @Override
    public NodeResponse getFullHierarchy(String rootPath) {
        NodeResponse response = new NodeResponse();

        List<Node> allNodes = nodeRepository.findByIdNodeStartingWith(rootPath);
        List<String> nodesIds = allNodes.stream().map(Node::getIdNode).toList();
        List<Description> descriptions = descriptionRepository.findAll();
        List<NodeParam> allParams = paramRepository.findParamsByNodeIds(nodesIds);

        allParams.forEach(param -> response.getParams().add(nodeMapper.toDto(param, descriptions)));
        allNodes.forEach(node -> response.getNodes().add(nodeMapper.toDto(node)));

        return response;
    }

    @Override
    public NodeResponse getHierarchy(String rootPath) {
        NodeResponse response = new NodeResponse();

        List<Node> allNodes = nodeRepository.findDirectChildren(rootPath);
        List<String> nodesIds = allNodes.stream().map(Node::getIdNode).toList();
        List<Description> descriptions = descriptionRepository.findAll();
        List<NodeParam> allParams = paramRepository.findParamsByNodeIds(nodesIds);

        allParams.forEach(param -> response.getParams().add(nodeMapper.toDto(param, descriptions)));
        allNodes.forEach(node -> response.getNodes().add(nodeMapper.toDto(node)));

        return response;
    }

    @Override
    public List<String> getSites() {
        return nodeRepository.findRootNodes().stream().map(Node::getIdNode).toList();
    }

    @Override
    public TemplateResponse getTemplates() {
        TemplateResponse response = new TemplateResponse();
        List<KeyValue> templates = templateRepository.findAll()
                .stream()
                .map(t -> new KeyValue(t.getId(), t.getName()))
                .toList();
        response.setTemplates(templates);
        return response;
    }

    @Override
    public void connectNode(String idNode, String userName) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "connectNode is not yet implemented");
    }
}
