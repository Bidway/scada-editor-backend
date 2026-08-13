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
 * применяет их через {@code GET /{id}/resolved} — запись в теги, а для строк без тега — в
 * состояние сессии (см. runtime).
 */
@RestController
@RequestMapping("/api/editor/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService service;

    @PostMapping
    public RecipeResponseDto create(
            @Valid @RequestBody RecipeCreateDto dto,
            @RequestHeader("X-Username") String userName) {
        return service.create(dto, userName);
    }

    @PutMapping("/{id}")
    public RecipeResponseDto update(
            @PathVariable Long id,
            @Valid @RequestBody RecipeCreateDto dto,
            @RequestHeader("X-Username") String userName) {
        return service.update(id, dto, userName);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, @RequestHeader("X-Username") String userName) {
        service.delete(id, userName);
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
    public ResolvedRecipeDto resolved(@PathVariable Long id) {
        return service.resolve(id);
    }
}
