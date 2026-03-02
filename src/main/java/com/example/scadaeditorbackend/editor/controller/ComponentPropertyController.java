package com.example.scadaeditorbackend.editor.controller;

import com.example.scadaeditorbackend.editor.model.ComponentProperty;
import com.example.scadaeditorbackend.editor.service.ComponentPropertyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/properties")
@RequiredArgsConstructor
public class ComponentPropertyController {

    private final ComponentPropertyService service;

    @PostMapping
    public ComponentProperty create(@RequestBody ComponentProperty property) {
        return service.create(property);
    }

    @PutMapping("/{id}")
    public ComponentProperty update(
            @PathVariable Long id,
            @RequestBody ComponentProperty property) {
        return service.update(id, property);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}")
    public ComponentProperty getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping("/component/{componentId}")
    public List<ComponentProperty> getByComponentId(
            @PathVariable Long componentId) {
        return service.getByComponentId(componentId);
    }
}
