package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeValueDto;
import com.example.editor.dto.recipe.ResolvedRecipeDto;
import com.example.editor.dto.recipe.ResolvedRecipeValueDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.recipe.RecipeTypes;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.recipe.RecipeFileStore;
import com.example.editor.service.RecipeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Наборы значений (рецепты) хранятся файлами через {@link RecipeFileStore} — один файл на
 * рецепт, {@code id} стабилен (слаг, выделяется один раз при создании и не меняется при
 * переименовании). Журнал правок (бывший {@code recipe_change}) не переносится: признан
 * некритичным для этого вида данных.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    private final RecipeFileStore fileStore;
    private final ComponentPropertyRepository propertyRepository;

    @Override
    public RecipeResponseDto create(RecipeCreateDto dto) {
        RecipeResponseDto recipe = new RecipeResponseDto();
        recipe.setName(dto.getName());
        recipe.setType(typeOrDefault(dto.getType()));
        recipe.setComponent_id(dto.getComponent_id());
        recipe.setValues(normalizedValues(dto.getValues()));
        return fileStore.create(recipe);
    }

    @Override
    public RecipeResponseDto update(String id, RecipeCreateDto dto) {
        RecipeResponseDto recipe = fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(dto.getName());
        recipe.setType(typeOrDefault(dto.getType()));
        recipe.setComponent_id(dto.getComponent_id());
        if (dto.getValues() != null) {
            recipe.setValues(normalizedValues(dto.getValues()));
        }
        return fileStore.update(recipe);
    }

    @Override
    public void delete(String id) {
        fileStore.findById(id).orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        fileStore.deleteById(id);
    }

    @Override
    public List<RecipeResponseDto> listByComponent(Long componentId) {
        return fileStore.findByComponentId(componentId);
    }

    @Override
    public RecipeResponseDto get(String id) {
        return fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
    }

    /**
     * Сопоставление значений набора со свойствами компонента по имени. Из свойства берутся тег
     * (может отсутствовать — тогда значение локальное) и value_type: рантайму он нужен для
     * коэрсинга значения перед записью.
     */
    @Override
    public ResolvedRecipeDto resolve(String id) {
        RecipeResponseDto recipe = fileStore.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));

        Map<String, ComponentProperty> propertiesByName = new HashMap<>();
        for (ComponentProperty property : propertyRepository.findByComponentId(recipe.getComponent_id())) {
            String name = normalize(property.getName());
            if (name == null) {
                continue;
            }
            ComponentProperty duplicate = propertiesByName.putIfAbsent(name, property);
            if (duplicate != null) {
                log.warn("Component {} has duplicate property name '{}' (ids {} and {}); "
                                + "recipe values resolve to the first one",
                        recipe.getComponent_id(), name, duplicate.getId(), property.getId());
            }
        }

        List<ResolvedRecipeValueDto> values = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (RecipeValueDto value : recipe.getValues()) {
            String propertyName = normalize(value.getProperty_name());
            ComponentProperty property = propertyName == null ? null : propertiesByName.get(propertyName);
            if (property == null) {
                unmatched.add(value.getProperty_name());
                continue;
            }
            values.add(new ResolvedRecipeValueDto(
                    propertyName, value.getValue(), property.getValueType(), property.getTagId()));
        }
        if (!unmatched.isEmpty()) {
            log.warn("Recipe {} has {} value(s) with no matching property in component {}: {}",
                    id, unmatched.size(), recipe.getComponent_id(), unmatched);
        }
        return new ResolvedRecipeDto(recipe.getId(), recipe.getComponent_id(), values, unmatched);
    }

    /**
     * Имя свойства обязательно и уникально в пределах набора — резолв берёт значение по имени,
     * второе значение на то же свойство осталось бы недостижимым.
     */
    private List<RecipeValueDto> normalizedValues(List<RecipeValueDto> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        Set<String> seen = new HashSet<>();
        List<RecipeValueDto> result = new ArrayList<>();
        for (RecipeValueDto v : values) {
            String propertyName = normalize(v.getProperty_name());
            if (propertyName == null) {
                throw new IllegalArgumentException("Recipe value requires property_name");
            }
            if (!seen.add(propertyName)) {
                throw new IllegalArgumentException(
                        "Duplicate value for property '" + propertyName
                                + "'; a property can have only one value in a set");
            }
            RecipeValueDto copy = new RecipeValueDto();
            copy.setProperty_name(propertyName);
            copy.setValue(v.getValue());
            result.add(copy);
        }
        return result;
    }

    private static String typeOrDefault(String type) {
        return (type == null || type.isBlank()) ? RecipeTypes.RECIPE : type.trim();
    }

    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
