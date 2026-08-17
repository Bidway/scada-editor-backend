package com.example.editor.controller;

import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.dto.property.PropertyResponseDto;
import com.example.editor.service.ComponentPropertyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/editor/properties")
@RequiredArgsConstructor
public class ComponentPropertyController {

    private final ComponentPropertyService service;

    @PostMapping
    public PropertyResponseDto create(
            @Valid @RequestBody PropertyCreateDto property,
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

    /**
     * {@code based_on_version} едет query-параметром, а не конвертом в теле: id уже в пути, и
     * тело несло бы ровно одно поле. Удаление компонентов (scada-ybr) взяло конверт по другой
     * причине — там тело было нужно под список ids.
     */
    @DeleteMapping("/{id}")
    public void delete(
            @PathVariable Long id,
            @RequestParam(required = false) Integer based_on_version,
            @RequestHeader("X-Username") String userName) {
        service.delete(id, userName, based_on_version);
    }
}
