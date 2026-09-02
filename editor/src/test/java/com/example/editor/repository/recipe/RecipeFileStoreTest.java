package com.example.editor.repository.recipe;

import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeValueDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeFileStoreTest {

    private RecipeFileStore store(Path dir) {
        return new RecipeFileStore(dir.toString(), new ObjectMapper());
    }

    private RecipeResponseDto recipe(Long componentId, String name) {
        RecipeResponseDto dto = new RecipeResponseDto();
        dto.setName(name);
        dto.setType("recipe");
        dto.setComponent_id(componentId);
        dto.setValues(List.of());
        return dto;
    }

    private static RecipeValueDto value(String propertyName, String value) {
        RecipeValueDto dto = new RecipeValueDto();
        dto.setProperty_name(propertyName);
        dto.setValue(value);
        return dto;
    }

    @Test
    void create_generatesStableId_persistsAndSupportsCollisionAndDelete(@TempDir Path dir) {
        RecipeFileStore store = store(dir);

        RecipeResponseDto first = store.create(recipe(991L, "Продукт А"));
        RecipeResponseDto second = store.create(recipe(991L, "Продукт А"));

        assertThat(first.getId()).isEqualTo("991-продукт-а");
        assertThat(second.getId()).isEqualTo("991-продукт-а-2");

        first.setName("Продукт Б");
        store.update(first);
        assertThat(store.findById(first.getId()).orElseThrow().getName()).isEqualTo("Продукт Б");

        // component_id 99 не должен ловить файлы component_id 991 по общему префиксу "99".
        store.create(recipe(99L, "Партия"));
        assertThat(store.findByComponentId(99L)).hasSize(1);

        store.deleteById(first.getId());
        assertThat(store.findById(first.getId())).isEmpty();
    }

    @Test
    void renameProperty_updatesMatchingValuesAcrossAllRecipesOfComponent(@TempDir Path dir) {
        RecipeFileStore store = store(dir);
        RecipeResponseDto recipeA = recipe(991L, "Продукт А");
        recipeA.setValues(List.of(value("Уставка", "10"), value("Режим", "1")));
        store.create(recipeA);
        RecipeResponseDto recipeB = recipe(991L, "Продукт Б");
        recipeB.setValues(List.of(value("Режим", "2")));
        store.create(recipeB);

        int moved = store.renameProperty(991L, "Уставка", "Скорость");

        assertThat(moved).isEqualTo(1);
        assertThat(store.findById(recipeA.getId()).orElseThrow().getValues())
                .extracting(RecipeValueDto::getProperty_name)
                .containsExactlyInAnyOrder("Скорость", "Режим");
    }
}
