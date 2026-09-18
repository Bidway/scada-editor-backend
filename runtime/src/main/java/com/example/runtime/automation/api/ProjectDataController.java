package com.example.runtime.automation.api;

import com.example.runtime.project.ProjectRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Перечитать таблицы данных проекта из editor — для скриптов проекта, шагов процедур и фоновых
 * задач разом. Путь прежний, с тех пор как данные читали только задачи {@code automation}.
 */
@RestController
@RequestMapping("/api/automation/projects/{projectId}/data")
@RequiredArgsConstructor
@Slf4j
public class ProjectDataController {

    private final ProjectRuntimeService projects;

    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reload(@PathVariable long projectId) {
        boolean running;
        try {
            running = projects.reloadData(projectId);
        } catch (RuntimeException e) {
            log.warn("Проект {}: данные проекта не перечитаны, остались прежние: {}", projectId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "editor_unavailable",
                    "message", "Данные проекта " + projectId
                            + " не перечитаны: editor не ответил. Действуют прежние"));
        }
        if (running) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "project_not_running",
                "message", "Проект " + projectId + " не поднят этим экземпляром runtime"));
    }
}
