package com.example.channel.service;

import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.CreateNodeResponse;
import com.example.channel.dto.nodeDto.NodeResponse;
import com.example.channel.dto.nodeDto.TemplateResponse;

import java.util.List;


public interface NodeService {

    void deleteNodeByIdNode(String idNode, String userName);
    CreateNodeResponse createNode(CreateNodeDto createNodeDTO, String userName);
    NodeResponse getFullHierarchy(String rootPath);
    NodeResponse getHierarchy(String rootPath);
    List<String> getSites();
    TemplateResponse getTemplates();
}
