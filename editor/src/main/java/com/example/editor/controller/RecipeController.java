package com.example.editor.controller;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.ResolvedRecipeValueDto;
import com.example.editor.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD рецептов (наборов уставок) для таблиц-компонентов. Рецепты задаются заранее (design-time);
 * рантайм применяет их через {@code GET /{id}/resolved} + запись значений в теги (см. runtime).
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
    public RecipeResponseDto update(@PathVariable Long id, @Valid @RequestBody RecipeCreateDto dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping
    public List<RecipeResponseDto> listByComponent(@RequestParam Long componentId) {
        return service.listByComponent(componentId);
    }

    @GetMapping("/{id}")
    public RecipeResponseDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/resolved")
    public List<ResolvedRecipeValueDto> resolved(@PathVariable Long id) {
        return service.resolve(id);
    }
}
