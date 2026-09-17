package com.example.runtime.assignment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Назначения экземплярам runtime. Отвечает любой экземпляр: запрос только пишет в базу, применяет его
 * экземпляр-адресат при ближайшем перечитывании (≤5 с). Не переадресуется.
 */
@RestController
@RequestMapping("/api/runtime/instances")
@RequiredArgsConstructor
public class InstanceController {

    public record TopicAssignmentRequest(String commandsTopic, String resultsTopic, List<String> pathPrefixes) {
    }

    private final AssignmentService service;

    @GetMapping
    public List<AssignmentService.InstanceView> list() {
        return service.listInstances();
    }

    @PutMapping("/{instanceId}/topics/{telemetryTopic}")
    public ResponseEntity<Void> assignTopic(@PathVariable String instanceId, @PathVariable String telemetryTopic,
                                            @RequestBody TopicAssignmentRequest body,
                                            @RequestHeader("X-Username") String username) {
        service.assignTopic(instanceId, telemetryTopic, body.commandsTopic(), body.resultsTopic(),
                body.pathPrefixes(), username);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{instanceId}/topics/{telemetryTopic}")
    public ResponseEntity<Void> removeTopic(@PathVariable String instanceId, @PathVariable String telemetryTopic) {
        service.removeTopic(instanceId, telemetryTopic);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{instanceId}/projects/{projectId}")
    public ResponseEntity<Void> assignProject(@PathVariable String instanceId, @PathVariable long projectId,
                                              @RequestHeader("X-Username") String username) {
        service.assignProject(instanceId, projectId, username);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{instanceId}/projects/{projectId}")
    public ResponseEntity<Void> removeProject(@PathVariable String instanceId, @PathVariable long projectId) {
        service.removeProject(instanceId, projectId);
        return ResponseEntity.noContent().build();
    }
}
