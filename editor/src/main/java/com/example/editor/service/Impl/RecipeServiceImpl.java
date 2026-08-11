package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeValueDto;
import com.example.editor.dto.recipe.ResolvedRecipeDto;
import com.example.editor.dto.recipe.ResolvedRecipeValueDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.recipe.Recipe;
import com.example.editor.model.recipe.RecipeTypes;
import com.example.editor.model.recipe.RecipeValue;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.recipe.RecipeRepository;
import com.example.editor.service.RecipeService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    private final RecipeRepository recipeRepository;
    private final ComponentPropertyRepository propertyRepository;

    @Override
    public RecipeResponseDto create(RecipeCreateDto dto, String userName) {
        Recipe recipe = new Recipe();
        recipe.setName(dto.getName());
        recipe.setType(typeOrDefault(dto.getType()));
        recipe.setComponentId(dto.getComponent_id());
        applyValues(recipe, dto.getValues());
        return toDto(recipeRepository.save(recipe));
    }

    @Override
    public RecipeResponseDto update(Long id, RecipeCreateDto dto, String userName) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));
        recipe.setName(dto.getName());
        recipe.setType(typeOrDefault(dto.getType()));
        recipe.setComponentId(dto.getComponent_id());
        applyValues(recipe, dto.getValues());
        return toDto(recipeRepository.save(recipe));
    }

    @Override
    public void delete(Long id, String userName) {
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

    /**
     * Сопоставление значений набора со строками таблицы по имени строки. Из строки берутся тег
     * (может отсутствовать — тогда строка локальная) и value_type: рантайму он нужен для
     * коэрсинга значения перед записью.
     */
    @Override
    public ResolvedRecipeDto resolve(Long id) {
        Recipe recipe = recipeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Recipe not found: " + id));

        Map<String, ComponentProperty> rowsByName = new HashMap<>();
        for (ComponentProperty property : propertyRepository.findByComponentId(recipe.getComponentId())) {
            String name = normalize(property.getName());
            if (name == null) {
                continue;
            }
            // Уникальность имён гарантируется при сохранении, но данные могли быть заведены
            // до её появления — тогда лучше шумнуть, чем молча взять произвольную строку.
            ComponentProperty duplicate = rowsByName.putIfAbsent(name, property);
            if (duplicate != null) {
                log.warn("Component {} has duplicate property name '{}' (ids {} and {}); "
                                + "recipe values resolve to the first one",
                        recipe.getComponentId(), name, duplicate.getId(), property.getId());
            }
        }

        List<ResolvedRecipeValueDto> values = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (RecipeValue value : recipe.getValues()) {
            String rowName = normalize(value.getRowName());
            ComponentProperty row = rowName == null ? null : rowsByName.get(rowName);
            if (row == null) {
                unmatched.add(value.getRowName());
                continue;
            }
            values.add(new ResolvedRecipeValueDto(rowName, value.getValue(), row.getValueType(), row.getTagId()));
        }
        if (!unmatched.isEmpty()) {
            log.warn("Recipe {} has {} value(s) with no matching row in component {}: {}",
                    id, unmatched.size(), recipe.getComponentId(), unmatched);
        }
        return new ResolvedRecipeDto(recipe.getId(), recipe.getComponentId(), values, unmatched);
    }

    /**
     * Синхронизация значений набора. Значение существующей строки правится на месте, а не
     * пересоздаётся: ключ здесь — имя строки, и оно же единственное, чем значение связано с
     * таблицей. Тот же приём, что у свойств, скриптов, состояний и обработчиков компонента
     * (см. {@code ComponentScriptBindingApplier}); ссылок по {@code RecipeValue.id} в контуре
     * нет, поэтому падений прежний clear + insert не давал — но правка одной уставки
     * переписывала весь набор целиком.
     * <p>
     * Два значения на одну строку отвергаются: резолв набора берёт строку по имени, и второе
     * значение всё равно осталось бы недостижимым.
     */
    private void applyValues(Recipe recipe, List<RecipeValueDto> values) {
        if (values == null) {
            recipe.getValues().clear();
            return;
        }
        Map<String, RecipeValue> existingByRow = new HashMap<>();
        for (RecipeValue existing : recipe.getValues()) {
            existingByRow.put(existing.getRowName(), existing);
        }

        List<RecipeValue> incoming = new ArrayList<>();
        Set<String> seenRows = new HashSet<>();
        for (RecipeValueDto v : values) {
            String rowName = normalize(v.getRow_name());
            if (rowName == null) {
                throw new IllegalArgumentException("Recipe value requires row_name");
            }
            if (!seenRows.add(rowName)) {
                throw new IllegalArgumentException(
                        "Duplicate value for row '" + rowName + "' in recipe " + recipe.getId()
                                + "; a row can have only one value in a set");
            }
            RecipeValue target = existingByRow.get(rowName);
            if (target == null) {
                target = new RecipeValue();
                target.setRecipe(recipe);
                target.setRowName(rowName);
            }
            target.setValue(v.getValue());
            incoming.add(target);
        }

        recipe.getValues().removeIf(existing -> !seenRows.contains(existing.getRowName()));
        for (RecipeValue target : incoming) {
            if (target.getId() == null) {
                recipe.getValues().add(target);
            }
        }
    }

    private static String typeOrDefault(String type) {
        return (type == null || type.isBlank()) ? RecipeTypes.RECIPE : type.trim();
    }

    /** Имя строки — ключ привязки, поэтому сравнивается и хранится без краевых пробелов. */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private RecipeResponseDto toDto(Recipe recipe) {
        RecipeResponseDto dto = new RecipeResponseDto();
        dto.setId(recipe.getId());
        dto.setName(recipe.getName());
        dto.setType(recipe.getType());
        dto.setComponent_id(recipe.getComponentId());
        dto.setValues(recipe.getValues().stream()
                .map(v -> {
                    RecipeValueDto d = new RecipeValueDto();
                    d.setRow_name(v.getRowName());
                    d.setValue(v.getValue());
                    return d;
                })
                .toList());
        return dto;
    }
}
