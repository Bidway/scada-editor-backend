package com.example.editor.controller;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentResponseDto;
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
    public List<ComponentResponseDto> create(@RequestBody List<ComponentCreateDto> components) {
        return service.create(components);
    }
    @PostMapping("/scene")
    public SceneCreateResponseDto createScene(@RequestBody SceneCreateDto scene) {
        return service.createScene(scene);
    }
    @GetMapping("/scenes")
    public List<ScenesResponseDto> getScenes(){
        return service.getScenes();
    }

    @PutMapping()
    public List<ComponentResponseDto> update(@RequestBody List<ComponentCreateDto> components) {
        return service.update(components);
    }

    @DeleteMapping()
    public void delete(@RequestBody List<Long> ids) {
        service.delete(ids);
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
