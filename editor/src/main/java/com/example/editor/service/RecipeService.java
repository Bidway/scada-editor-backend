package com.example.editor.service;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.ResolvedRecipeDto;

import java.util.List;

public interface RecipeService {

    RecipeResponseDto create(RecipeCreateDto dto, String userName);

    RecipeResponseDto update(Long id, RecipeCreateDto dto, String userName);

    void delete(Long id, String userName);

    List<RecipeResponseDto> listByComponent(Long componentId);

    RecipeResponseDto get(Long id);

    /**
     * Значения набора, дополненные тегом и типом строки (сопоставление по имени строки), —
     * для применения рантаймом. Строки набора, которых в таблице больше нет, возвращаются
     * отдельным списком, а не отбрасываются.
     */
    ResolvedRecipeDto resolve(Long id);
}
