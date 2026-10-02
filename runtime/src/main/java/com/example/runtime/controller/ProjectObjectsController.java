package com.example.runtime.controller;

import com.example.runtime.project.ProjectModel;
import com.example.runtime.project.ProjectObjects;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.recipe.ProjectNotInOperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Список объектов prod-выпуска для инспектора объектов монитора. */
@RestController
@RequestMapping("/api/runtime/projects/{projectId}/objects")
@RequiredArgsConstructor
public class ProjectObjectsController {

    private final ProjectRuntimeStore store;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> objects(@PathVariable Long projectId) {
        ProjectRuntime project = store.get(projectId);
        if (project == null) {
            throw new ProjectNotInOperationException(projectId);
        }
        // Модель — один раз: номер выпуска в заголовке и список обязаны быть из одного выпуска.
        ProjectModel model = project.getModel();
        return ResponseEntity.ok()
                .header(ProjectScenesController.RELEASE_HEADER, String.valueOf(model.versionNo()))
                .body(ProjectObjects.list(model.rawTree()));
    }
}
