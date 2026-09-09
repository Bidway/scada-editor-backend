package com.example.editor.service;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;

public interface RecipeService {

    RecipeResponseDto create(RecipeCreateDto dto);

    RecipeResponseDto update(String id, RecipeCreateDto dto);

    void delete(String id);

    RecipeResponseDto get(String id);
}
