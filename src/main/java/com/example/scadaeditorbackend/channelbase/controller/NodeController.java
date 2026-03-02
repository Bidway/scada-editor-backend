package com.example.scadaeditorbackend.channelbase.controller;

import com.example.scadaeditorbackend.channelbase.dto.nodeDto.CreateNodeDto;
import com.example.scadaeditorbackend.channelbase.dto.nodeDto.CreateNodeResponse;
import com.example.scadaeditorbackend.channelbase.dto.nodeDto.NodeResponse;
import com.example.scadaeditorbackend.channelbase.service.NodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/node")
@RequiredArgsConstructor
public class NodeController {
    private final NodeService nodeService;

    @DeleteMapping("/{idNode}")
    public ResponseEntity<Void> deleteNode(@PathVariable String idNode) {
        nodeService.deleteNodeByIdNode(idNode);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("")
    public ResponseEntity<CreateNodeResponse> createNode(@RequestBody CreateNodeDto createNodeDTO) {
        CreateNodeResponse response = nodeService.createNode(createNodeDTO);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/all")
    public ResponseEntity<NodeResponse> getFullHierarchy(
            @RequestParam String site,
            @RequestParam String project) {
        return ResponseEntity.ok(nodeService.getFullHierarchy(site, project));
    }

}