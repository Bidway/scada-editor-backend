package com.example.channel.controller;

import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.example.channel.dto.nodeDto.CreateNodeResponse;
import com.example.channel.dto.nodeDto.NodeResponse;
import com.example.channel.dto.nodeDto.TemplateResponse;
import com.example.channel.service.NodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/channel/node")
@RequiredArgsConstructor
public class NodeController {
    private final NodeService nodeService;

    @DeleteMapping("/{idNode}")
    public ResponseEntity<Void> deleteNode(@PathVariable String idNode,
                                           @RequestHeader("X-Username") String userName) {
        nodeService.deleteNodeByIdNode(idNode, userName);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("")
    public ResponseEntity<CreateNodeResponse> createNode(@RequestBody CreateNodeDto createNodeDTO,
                                                         @RequestHeader("X-Username") String userName) {
        CreateNodeResponse response = nodeService.createNode(createNodeDTO, userName);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/fullHierarchy")
    public ResponseEntity<NodeResponse> getFullHierarchy(
            @RequestParam String rootPath) {
        return ResponseEntity.ok(nodeService.getFullHierarchy(rootPath));
    }
    @GetMapping("/hierarchy")
    public ResponseEntity<NodeResponse> getHierarchy(
            @RequestParam String rootPath) {
        return ResponseEntity.ok(nodeService.getHierarchy(rootPath));
    }
    @GetMapping("/sites")
    public List<String> getSites() {
        return nodeService.getSites();
    }
    @GetMapping("/templates")
    public TemplateResponse getTemplates() {
        return nodeService.getTemplates();
    }

}