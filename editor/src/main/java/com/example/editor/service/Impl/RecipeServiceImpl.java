package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeValueDto;
import com.example.editor.dto.recipe.ResolvedRecipeValueDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.recipe.Recipe;
import com.example.editor.model.recipe.RecipeValue;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.recipe.RecipeRepository;
import com.example.editor.service.RecipeService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class RecipeServiceImpl implements RecipeService {

    private final RecipeRepository recipeRepository;
    private final ComponentPropertyRepository propertyRepository;

    @Override
    public RecipeResponseDto create(RecipeCreateDto dto) {
        Recipe recipe = new Recipe();
        recipe.setName(dto.getName());
        recipe.setComponentId(dto.getComponent_id());
        applyValues(recipe, dto.getValues());
        return toDto(recipeRepository.save(recipe));
    }

    @Override
    public RecipeResponseDto update(Long id, RecipeCreateDto dto) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(dto.getName());
        recipe.setComponentId(dto.getComponent_id());
        applyValues(recipe, dto.getValues());
        return toDto(recipeRepository.save(recipe));
    }

    @Override
    public void delete(Long id) {
        if (!recipeRepository.existsById(id)) {
            throw new NotFoundException("Recipe not found: " + id);
        }
        recipeRepository.deleteById(id);
    }

    @Override
    public List<RecipeResponseDto> listByComponent(Long componentId) {
        return recipeRepository.findByComponentId(componentId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public RecipeResponseDto get(Long id) {
        return toDto(recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id)));
    }

    @Override
    public List<ResolvedRecipeValueDto> resolve(Long id) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        // value_type берём из строк таблицы по tag_id — рантайму он нужен для коэрсинга перед записью.
        Map<String, String> valueTypeByTag = propertyRepository.findByComponentId(recipe.getComponentId()).stream()
                .filter(p -> p.getTagId() != null)
                .collect(Collectors.toMap(ComponentProperty::getTagId, ComponentProperty::getValueType, (a, b) -> a));
        return recipe.getValues().stream()
                .map(v -> new ResolvedRecipeValueDto(v.getTagId(), v.getValue(), valueTypeByTag.get(v.getTagId())))
                .toList();
    }

    private void applyValues(Recipe recipe, List<RecipeValueDto> values) {
        recipe.getValues().clear();
        if (values == null) {
            return;
        }
        for (RecipeValueDto v : values) {
            recipe.getValues().add(RecipeValue.builder()
                    .tagId(v.getTag_id())
                    .value(v.getValue())
                    .recipe(recipe)
                    .build());
        }
    }

    private RecipeResponseDto toDto(Recipe recipe) {
        RecipeResponseDto dto = new RecipeResponseDto();
        dto.setId(recipe.getId());
        dto.setName(recipe.getName());
        dto.setComponent_id(recipe.getComponentId());
        dto.setValues(recipe.getValues().stream()
                .map(v -> {
                    RecipeValueDto d = new RecipeValueDto();
                    d.setTag_id(v.getTagId());
                    d.setValue(v.getValue());
                    return d;
                })
                .toList());
        return dto;
    }
}
