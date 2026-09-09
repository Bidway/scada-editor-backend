package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeStepActionDto;
import com.example.editor.dto.recipe.RecipeStepDto;
import com.example.editor.dto.recipe.RecipeTagDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.repository.recipe.RecipeFileStore;
import com.example.editor.service.RecipeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Процедурные рецепты хранятся файлами через {@link RecipeFileStore} — один файл на
 * рецепт, {@code id} стабилен (слаг из имени, не меняется при переименовании).
 */
@Service
@RequiredArgsConstructor
public class RecipeServiceImpl implements RecipeService {

    private final RecipeFileStore fileStore;

    @Override
    public RecipeResponseDto create(RecipeCreateDto dto) {
        RecipeResponseDto recipe = new RecipeResponseDto();
        recipe.setName(dto.getName());
        recipe.setTags(dto.getTags() == null ? List.of() : dto.getTags());
        recipe.setSteps(dto.getSteps());
        validate(recipe);
        return fileStore.create(recipe);
    }

    @Override
    public RecipeResponseDto update(String id, RecipeCreateDto dto) {
        RecipeResponseDto recipe = fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(dto.getName());
        recipe.setTags(dto.getTags() == null ? List.of() : dto.getTags());
        recipe.setSteps(dto.getSteps());
        validate(recipe);
        return fileStore.update(recipe);
    }

    @Override
    public void delete(String id) {
        fileStore.findById(id).orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        fileStore.deleteById(id);
    }

    @Override
    public List<RecipeResponseDto> list() {
        return fileStore.findAll();
    }

    @Override
    public RecipeResponseDto get(String id) {
        return fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
    }

    /**
     * Каждый {@code action.tag} шага должен существовать в манифесте {@code tags}, а если у
     * тега манифеста задан {@code value_type} — значение в JSON должно ему соответствовать.
     * Опечатка в имени тега или несовпадение типа отклоняются здесь, а не тихо уезжают в
     * файл, откуда всплывут только при исполнении рецепта в runtime.
     */
    private void validate(RecipeResponseDto recipe) {
        Map<String, RecipeTagDto> tagsByName = new HashMap<>();
        for (RecipeTagDto tag : recipe.getTags()) {
            tagsByName.put(tag.getName(), tag);
        }
        Set<String> unknown = new HashSet<>();
        for (RecipeStepDto step : recipe.getSteps()) {
            if (step.getAction() == null) {
                continue;
            }
            for (RecipeStepActionDto action : step.getAction()) {
                RecipeTagDto tag = tagsByName.get(action.getTag());
                if (tag == null) {
                    unknown.add(action.getTag());
                    continue;
                }
                requireTypeMatch(tag, action);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Recipe step action references tag(s) not declared in manifest 'tags': " + unknown);
        }
    }

    private void requireTypeMatch(RecipeTagDto tag, RecipeStepActionDto action) {
        String valueType = tag.getValue_type();
        if (valueType == null || valueType.isBlank()) {
            return;
        }
        Object value = action.getValue();
        boolean matches = switch (valueType) {
            case "number" -> value instanceof Number;
            case "bool" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            default -> true;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "Value for tag '" + tag.getName() + "' does not match declared value_type '"
                            + valueType + "': " + value);
        }
    }
}
