package com.example.editor.controller;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.service.ComponentPropertyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/editor/properties")
@RequiredArgsConstructor
public class ComponentPropertyController {

    private final ComponentPropertyService service;

    @PostMapping
    public PropertyResponseDto create(@RequestBody PropertyCreateDto property) {
        return service.create(property);
    }

    @PutMapping("/{id}")
    public PropertyResponseDto update(
            @PathVariable Long id,
            @RequestBody PropertyCreateDto property) {
        return service.update(id, property);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
