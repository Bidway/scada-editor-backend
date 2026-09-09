package com.example.editor.controller;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD процедурных рецептов: создание, обновление, удаление, получение.
 * Рецепты хранятся в файлах ({@code RecipeFileStore}), один файл на рецепт.
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

    @GetMapping("/{id}")
    public RecipeResponseDto get(@PathVariable String id) {
        return service.get(id);
    }
}
