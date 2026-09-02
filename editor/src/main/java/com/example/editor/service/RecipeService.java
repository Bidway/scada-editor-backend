package com.example.editor.service;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.ResolvedRecipeDto;

import java.util.List;

public interface RecipeService {

    RecipeResponseDto create(RecipeCreateDto dto);

    RecipeResponseDto update(String id, RecipeCreateDto dto);

    void delete(String id);

    List<RecipeResponseDto> listByComponent(Long componentId);

    RecipeResponseDto get(String id);

    /**
     * Значения набора, дополненные тегом и типом (сопоставление по имени свойства), — для
     * применения рантаймом. Значения набора, которым в таблице больше нет соответствующего
     * свойства, возвращаются отдельным списком, а не отбрасываются.
     */
    ResolvedRecipeDto resolve(String id);
}
