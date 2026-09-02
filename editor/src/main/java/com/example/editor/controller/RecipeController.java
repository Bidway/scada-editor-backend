package com.example.editor.controller;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.ResolvedRecipeDto;
import com.example.editor.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD именованных наборов значений для таблиц-компонентов — рецептов, параметров станции и
 * т.п. (различаются полем {@code type}). Наборы задаются заранее (design-time); рантайм
 * применяет их через {@code GET /{id}/resolved} — запись в теги, а для значений без тега — в
 * состояние сессии (см. runtime). Хранилище — файлы ({@code RecipeFileStore}), контракт REST
 * не меняется.
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
    public List<RecipeResponseDto> listByComponent(@RequestParam Long componentId) {
        return service.listByComponent(componentId);
    }

    @GetMapping("/{id}")
    public RecipeResponseDto get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/resolved")
    public ResolvedRecipeDto resolved(@PathVariable String id) {
        return service.resolve(id);
    }
}
