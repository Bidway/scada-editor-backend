package com.example.automation.api;

import com.example.automation.engine.ProjectRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Перечитать таблицы данных проекта из editor. Отвечает только экземпляр, который исполняет
 * проект: при нескольких экземплярах gateway может попасть не в него (спека, «Отложено»).
 */
@RestController
@RequestMapping("/api/automation/projects/{projectId}/data")
@RequiredArgsConstructor
public class ProjectDataController {

    private final ProjectRegistry registry;

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(@PathVariable long projectId) {
        if (registry.reloadData(projectId)) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "project_not_running",
                "message", "Проект " + projectId + " не исполняется этим экземпляром automation"));
    }
}
