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
    public PropertyResponseDto create(
            @RequestBody PropertyCreateDto property,
            @RequestHeader("X-Username") String userName) {
        return service.create(property, userName);
    }

    @PutMapping("/{id}")
    public PropertyResponseDto update(
            @PathVariable Long id,
            @RequestBody PropertyCreateDto property,
            @RequestHeader("X-Username") String userName) {
        return service.update(id, property, userName);
    }

    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable Long id,
            @RequestHeader("X-Username") String userName) {
        service.delete(id, userName);
    }
}
