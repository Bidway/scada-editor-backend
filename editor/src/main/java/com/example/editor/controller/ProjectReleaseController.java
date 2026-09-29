package com.example.editor.controller;

import com.example.editor.dto.release.ProjectReleaseDto;
import com.example.editor.service.release.ProjectReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Создание выпуска. Список и чтение выпусков — общий DocumentVersionController
 * ({@code /api/editor/projects/{id}/versions[/{n}]}): у него только GET на этом пути.
 */
@RestController
@RequiredArgsConstructor
public class ProjectReleaseController {

    private final ProjectReleaseService service;

    @PostMapping("/api/editor/projects/{projectId}/versions")
    public ProjectReleaseDto release(@PathVariable Long projectId,
                                     @RequestBody(required = false) Map<String, String> body,
                                     @RequestHeader("X-Username") String userName) {
        return service.release(projectId, body == null ? null : body.get("comment"), userName);
    }
}
