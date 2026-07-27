package com.example.runtime.controller;

import com.example.runtime.dto.ApplyRecipeRequest;
import com.example.runtime.dto.ApplyRecipeResult;
import com.example.runtime.recipe.RecipeApplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Применение рецептов (уставок) в мониторинге: по действию оператора все значения рецепта
 * записываются в теги → ПЛК. Значения берутся из editor по recipeId (server-authoritative).
 */
@RestController
@RequestMapping("/api/runtime/recipes")
@RequiredArgsConstructor
@Tag(name = "Recipes", description = "Применение рецептов (уставок) в ПЛК")
public class RecipeApplyController {

    private final RecipeApplyService recipeApplyService;

    @Operation(summary = "Применить рецепт: записать все уставки в теги → ПЛК")
    @PostMapping("/apply")
    public ResponseEntity<ApplyRecipeResult> apply(@Valid @RequestBody ApplyRecipeRequest request) {
        return ResponseEntity.ok(recipeApplyService.apply(request.getRecipeId()));
    }
}
