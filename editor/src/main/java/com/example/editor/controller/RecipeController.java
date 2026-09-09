package com.example.editor.controller;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD процедурных рецептов — упорядоченных шагов с действием и условием перехода.
 * Хранилище — файлы ({@code RecipeFileStore}). Исполняет рецепт {@code runtime}
 * (`/api/runtime/recipes/{id}/start` и далее), забирая определение отсюда как есть —
 * без предварительного резолва тег-путей.
 */
@RestController
@RequestMapping("/api/editor/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService service;

    @PostMapping
    public RecipeResponseDto create(@Valid @RequestBody RecipeCreateDto dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public RecipeResponseDto update(@PathVariable String id, @Valid @RequestBody RecipeCreateDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping
    public List<RecipeResponseDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public RecipeResponseDto get(@PathVariable String id) {
        return service.get(id);
    }
}
