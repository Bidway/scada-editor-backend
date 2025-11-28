package com.example.scadaeditorbackend.service.imlp;

import com.example.scadaeditorbackend.config.NodeTemplates;
import com.example.scadaeditorbackend.dto.nodeDto.CreateNodeDto;
import com.example.scadaeditorbackend.dto.nodeDto.CreateNodeResponse;
import com.example.scadaeditorbackend.dto.nodeDto.NodeDto;
import com.example.scadaeditorbackend.dto.nodeDto.NodeResponse;
import com.example.scadaeditorbackend.dto.paramDto.ParamDto;
import com.example.scadaeditorbackend.mapper.NodeMapper;
import com.example.scadaeditorbackend.model.Description;
import com.example.scadaeditorbackend.model.Node;
import com.example.scadaeditorbackend.model.NodeParam;
import com.example.scadaeditorbackend.repository.DescriptionRepository;
import com.example.scadaeditorbackend.repository.NodeRepository;
import com.example.scadaeditorbackend.repository.ParamRepository;
import com.example.scadaeditorbackend.service.NodeService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NodeServiceImpl implements NodeService {
    private final NodeRepository nodeRepository;
    private final DescriptionRepository descriptionRepository;
    private final ParamRepository paramRepository;
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


        List<Long> paramIds = NodeTemplates.getTemplateParams(createNodeDTO.getType());
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
