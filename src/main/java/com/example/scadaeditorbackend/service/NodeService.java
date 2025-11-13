package com.example.scadaeditorbackend.service;

import com.example.scadaeditorbackend.dto.*;
import com.example.scadaeditorbackend.model.Node;
import org.springframework.http.ResponseEntity;

import java.util.List;


public interface NodeService {

    void deleteNode(Long id);
    void deleteNodeByIdNode(String idNode);
    CreateNodeResponse createNode(CreateNodeDTO createNodeDTO);
    NodeResponse getFullHierarchy(String site, String project);

}
