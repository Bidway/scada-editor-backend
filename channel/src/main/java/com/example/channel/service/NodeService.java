package com.example.channel.service;

import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.CreateNodeResponse;
import com.example.channel.dto.nodeDto.NodeResponse;


public interface NodeService {

    void deleteNodeByIdNode(String idNode, Long userId);
    CreateNodeResponse createNode(CreateNodeDto createNodeDTO, Long userId);
    NodeResponse getFullHierarchy(String rootPath);
    NodeResponse getHierarchy(String rootPath);

}
