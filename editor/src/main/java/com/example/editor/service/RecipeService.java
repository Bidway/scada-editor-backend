package com.example.editor.service;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.ResolvedRecipeValueDto;

import java.util.List;

public interface RecipeService {

    RecipeResponseDto create(RecipeCreateDto dto);

    RecipeResponseDto update(Long id, RecipeCreateDto dto);

    void delete(Long id);

    List<RecipeResponseDto> listByComponent(Long componentId);

    RecipeResponseDto get(Long id);

    /** Значения рецепта, дополненные value_type строки (по tag_id) — для применения рантаймом. */
    List<ResolvedRecipeValueDto> resolve(Long id);
}
