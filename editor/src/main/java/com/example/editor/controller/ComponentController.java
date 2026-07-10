package com.example.editor.controller;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
import com.example.editor.dto.project.ProjectCreateDto;
import com.example.editor.dto.project.ProjectCreateResponseDto;
import com.example.editor.dto.project.ProjectsResponseDto;
import com.example.editor.dto.scene.SceneCreateDto;
import com.example.editor.dto.scene.SceneCreateResponseDto;
import com.example.editor.dto.scene.ScenesResponseDto;
import com.example.editor.model.component.Component;
import com.example.editor.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/editor/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService service;

    @PostMapping
    public List<ComponentResponseDto> create(
            @RequestBody List<ComponentCreateDto> components,
            @RequestHeader("X-Username") String userName) {
        return service.create(components, userName);
    }

    @PostMapping("/project")
    public ProjectCreateResponseDto createProject(
            @RequestBody ProjectCreateDto project,
            @RequestHeader("X-Username") String userName) {
        return service.createProject(project, userName);
    }

    @GetMapping("/projects")
    public List<ProjectsResponseDto> getProjects() {
        return service.getProjects();
    }

    @PostMapping("/scene")
    public SceneCreateResponseDto createScene(
            @RequestBody SceneCreateDto scene,
            @RequestHeader("X-Username") String userName) {
        return service.createScene(scene, userName);
    }

    @GetMapping("/scenes")
    public List<ScenesResponseDto> getScenes(@RequestParam(required = false) Long projectId) {
        return service.getScenes(projectId);
    }

    @PutMapping
    public List<ComponentResponseDto> update(
            @RequestBody List<ComponentCreateDto> components,
            @RequestHeader("X-Username") String userName) {
        return service.update(components, userName);
    }

    @DeleteMapping
    public void delete(
            @RequestBody List<Long> ids,
            @RequestHeader("X-Username") String userName) {
        service.delete(ids, userName);
    }

    @GetMapping("/{id}")
    public ComponentResponseDto getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<Component> getAll() {
        return service.getAll();
    }
}
