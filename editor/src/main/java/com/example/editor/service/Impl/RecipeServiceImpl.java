package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.repository.recipe.RecipeFileStore;
import com.example.editor.service.RecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Процедурные рецепты хранятся файлами через {@link RecipeFileStore} — один файл на
 * рецепт, {@code id} стабилен (слаг, выделяется один раз при создании и не меняется при
 * переименовании).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    private final RecipeFileStore fileStore;

    @Override
    public RecipeResponseDto create(RecipeCreateDto dto) {
        RecipeResponseDto recipe = new RecipeResponseDto();
        recipe.setName(dto.getName());
        recipe.setTags(dto.getTags());
        recipe.setSteps(dto.getSteps());
        return fileStore.create(recipe);
    }

    @Override
    public RecipeResponseDto update(String id, RecipeCreateDto dto) {
        RecipeResponseDto recipe = fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(dto.getName());
        recipe.setTags(dto.getTags());
        recipe.setSteps(dto.getSteps());
        return fileStore.update(recipe);
    }

    @Override
    public void delete(String id) {
        fileStore.findById(id).orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        fileStore.deleteById(id);
    }

    @Override
    public RecipeResponseDto get(String id) {
        return fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
    }
}
