package com.example.runtime.automation.api;

import com.example.runtime.automation.engine.AutomationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Перечитать таблицы данных проекта из editor для его фоновых задач. Путь прежний. */
@RestController
@RequestMapping("/api/automation/projects/{projectId}/data")
@RequiredArgsConstructor
public class ProjectDataController {

    private final AutomationEngine engine;

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(@PathVariable long projectId) {
        if (engine.reloadData(projectId)) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "project_not_running",
                "message", "Фоновые задачи проекта " + projectId
                        + " не исполняются: проект не поднят или у него нет задач"));
    }
}
