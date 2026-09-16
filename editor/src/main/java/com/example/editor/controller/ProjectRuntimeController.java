package com.example.editor.controller;

import com.example.editor.model.ProjectRuntimeFlag;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.service.RuntimeProjectsPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ввод проекта в эксплуатацию. Пока флаг не выставлен, runtime проект не поднимает:
 * ни телеметрии, ни onChange, ни процедур.
 */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/runtime")
@RequiredArgsConstructor
@Slf4j
public class ProjectRuntimeController {

    private final ProjectRuntimeFlagRepository repository;
    private final RuntimeProjectsPublisher publisher;

    @GetMapping
    public Map<String, Object> get(@PathVariable Long projectId) {
        boolean inOperation = repository.findById(projectId)
                .map(ProjectRuntimeFlag::isInOperation)
                .orElse(false);
        return Map.of("projectId", projectId, "inOperation", inOperation);
    }

    @PutMapping
    public Map<String, Object> set(@PathVariable Long projectId,
                                   @RequestBody Map<String, Boolean> body,
                                   @RequestHeader(value = "X-Username", required = false) String username) {
        boolean inOperation = Boolean.TRUE.equals(body.get("inOperation"));
        ProjectRuntimeFlag flag = repository.findById(projectId).orElseGet(() -> {
            ProjectRuntimeFlag created = new ProjectRuntimeFlag();
            created.setProjectId(projectId);
            return created;
        });
        flag.setInOperation(inOperation);
        repository.save(flag);
        publisher.publish(projectId, inOperation);
        log.info("Проект {} {} эксплуатацию (пользователь {})",
                projectId, inOperation ? "введён в" : "выведен из", username);
        return Map.of("projectId", projectId, "inOperation", inOperation);
    }
}
