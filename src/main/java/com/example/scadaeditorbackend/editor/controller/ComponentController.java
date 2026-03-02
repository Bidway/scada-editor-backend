package com.example.scadaeditorbackend.editor.controller;

import com.example.scadaeditorbackend.editor.dto.ComponentCreateDto;
import com.example.scadaeditorbackend.editor.dto.ComponentResponseDto;
import com.example.scadaeditorbackend.editor.model.Component;
import com.example.scadaeditorbackend.editor.service.ComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService service;

    @PostMapping
    public List<ComponentResponseDto> create(@RequestBody List<ComponentCreateDto> components) {
        return service.create(components);
    }

    @PutMapping("/{id}")
    public Component update(
            @PathVariable Long id,
            @RequestBody Component component) {
        return service.update(id, component);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/{id}")
    public Component getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @GetMapping
    public List<Component> getAll() {
        return service.getAll();
    }
}
