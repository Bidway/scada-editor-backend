package com.example.scadaeditorbackend.channelbase.service;

import com.example.scadaeditorbackend.channelbase.dto.nodeDto.CreateNodeDto;
import com.example.scadaeditorbackend.channelbase.dto.nodeDto.CreateNodeResponse;
import com.example.scadaeditorbackend.channelbase.dto.nodeDto.NodeResponse;


public interface NodeService {

    void deleteNode(Long id);
    void deleteNodeByIdNode(String idNode);
    CreateNodeResponse createNode(CreateNodeDto createNodeDTO);
    NodeResponse getFullHierarchy(String site, String project);

}
