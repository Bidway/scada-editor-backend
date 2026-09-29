package com.example.runtime.controller;

import com.example.runtime.project.ProjectModel;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.project.ProjectScenes;
import com.example.runtime.recipe.ProjectNotInOperationException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Схема для монитора — из prod-выпуска, который крутит этот экземпляр. Из editor монитор её не
 * берёт: там черновик, и экран разошёлся бы с исполняемой логикой.
 */
@RestController
@RequestMapping("/api/runtime/projects/{projectId}/scenes")
@RequiredArgsConstructor
public class ProjectScenesController {

    static final String RELEASE_HEADER = "X-Release-Version";

    private final ProjectRuntimeStore store;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> scenes(@PathVariable Long projectId) {
        ProjectModel model = modelOf(projectId);
        return ResponseEntity.ok().header(RELEASE_HEADER, String.valueOf(model.versionNo()))
                .body(ProjectScenes.list(model.rawTree()));
    }

    @GetMapping("/{sceneId}")
    public ResponseEntity<JsonNode> scene(@PathVariable Long projectId, @PathVariable long sceneId) {
        ProjectModel model = modelOf(projectId);
        JsonNode scene = ProjectScenes.find(model.rawTree(), sceneId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Сцены " + sceneId + " нет в выпуске " + model.versionNo()));
        return ResponseEntity.ok().header(RELEASE_HEADER, String.valueOf(model.versionNo())).body(scene);
    }

    private ProjectModel modelOf(Long projectId) {
        ProjectRuntime project = store.get(projectId);
        if (project == null) {
            throw new ProjectNotInOperationException(projectId);
        }
        return project.getModel();
    }
}
