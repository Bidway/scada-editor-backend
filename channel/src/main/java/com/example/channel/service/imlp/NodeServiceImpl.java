package com.example.channel.service.imlp;

import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.CreateNodeResponse;
import com.example.channel.dto.nodeDto.NodeDto;
import com.example.channel.dto.nodeDto.NodeResponse;
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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NodeServiceImpl implements NodeService {
    private final NodeRepository nodeRepository;
    private final DescriptionRepository descriptionRepository;
    private final ParamRepository paramRepository;
    private final TemplateRepository templateRepository;
    private final NodeMapper nodeMapper;

    @Override
    public void deleteNode(Long id) {
        nodeRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteNodeByIdNode(String idNode) {
        nodeRepository.deleteNodeByIdNode(idNode);
    }


    @Override
    public CreateNodeResponse createNode(CreateNodeDto createNodeDTO) {
        CreateNodeResponse response = new CreateNodeResponse();
        validateNodeType(createNodeDTO.getType());

        Node node = nodeMapper.toEntity(createNodeDTO);
        Node savedNode = nodeRepository.save(node);
        savedNode.updateIdNode();
        boolean isParent = "cha".equals(createNodeDTO.getType());
        response.setNodeDTO(nodeMapper.toDto(savedNode, isParent));
        List<Long> paramIds = new ArrayList<>();
        templateRepository.findByNameWithParams(createNodeDTO.getType()).getTemplateParams()
                .stream().forEach(param ->
                paramIds.add(param.getDescriptionId())
        );
//        List<Long> paramIds = NodeTemplates.getTemplateParams(createNodeDTO.getType());
        List<Description> descriptions = descriptionRepository.findAll();

        if (paramIds != null) {
            for (Long paramId : paramIds) {
                NodeParam nodeParam = new NodeParam();

                nodeParam.setIdType(paramId);
                nodeParam.setNode(savedNode);
                nodeParam.setValue("");

                NodeParam savedParam = paramRepository.save(nodeParam);
                ParamDto dto = nodeMapper.toDto(savedParam,descriptions);

                response.getParams().add(dto);
            }
        }

        return response;
    }

    private void validateNodeType(String type) {
        if (!List.of("dev", "sub", "cha").contains(type)) {
            throw new IllegalArgumentException("Node type must be 'dev', 'sub' or 'cha'");
        }
    }

    @Override
    public NodeResponse getFullHierarchy(String site, String project) {
        NodeResponse response = new NodeResponse();

        List<Node> devices = nodeRepository.findDevicesBySiteAndProject(site, project);
        List<Node> allNodes = new ArrayList<>(devices);
        List<Node> subtypes = findByParentIds(devices);
        allNodes.addAll(subtypes);
        List<Node> channels = findByParentIds(subtypes);
        allNodes.addAll(channels);

        List<String> nodesIds = allNodes.stream().map(Node::getIdNode).toList();

        List<Description> descriptions = descriptionRepository.findAll();

        List<NodeParam> allParams = paramRepository.findParamsByNodeIds(nodesIds);

        allParams.forEach(param -> {
           ParamDto dto = nodeMapper.toDto(param,descriptions);
           response.getParams().add(dto);
        });

        allNodes.forEach(node ->{
            NodeDto dto = nodeMapper.toDto(node);
            response.getNodes().add(dto);
        });
        return response;
    }

    // Универсальный метод для поиска дочерних узлов
    private List<Node> findByParentIds(List<Node> parents) {
        if (parents.isEmpty()) return Collections.emptyList();
        List<String> parentIds = parents.stream().map(Node::getIdNode).toList();
        return nodeRepository.findByParentIds(parentIds);
    }

}
