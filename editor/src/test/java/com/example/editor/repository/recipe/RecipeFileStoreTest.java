package com.example.editor.repository.recipe;

import com.example.editor.dto.recipe.RecipeResponseDto;
import com.example.editor.dto.recipe.RecipeStepActionDto;
import com.example.editor.dto.recipe.RecipeStepDto;
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

    private RecipeResponseDto recipe(String name) {
        RecipeResponseDto dto = new RecipeResponseDto();
        dto.setName(name);
        dto.setTags(List.of());
        RecipeStepDto step = new RecipeStepDto();
        step.setName("Шаг 1");
        step.setAction(List.of());
        dto.setSteps(List.of(step));
        return dto;
    }

    @Test
    void create_generatesStableId_persistsAndSupportsCollisionAndDelete(@TempDir Path dir) {
        RecipeFileStore store = store(dir);

        RecipeResponseDto first = store.create(recipe("Мойка щёлочью"));
        RecipeResponseDto second = store.create(recipe("Мойка щёлочью"));

        assertThat(first.getId()).isEqualTo("мойка-щёлочью");
        assertThat(second.getId()).isEqualTo("мойка-щёлочью-2");

        first.setName("Мойка щёлочью v2");
        store.update(first);
        assertThat(store.findById(first.getId()).orElseThrow().getName()).isEqualTo("Мойка щёлочью v2");

        assertThat(store.findAll()).hasSize(2);

        store.deleteById(first.getId());
        assertThat(store.findById(first.getId())).isEmpty();
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void roundTrips_stepsWithActionAndConditionScript(@TempDir Path dir) {
        RecipeFileStore store = store(dir);
        RecipeResponseDto recipe = recipe("Тест");
        RecipeStepActionDto action = new RecipeStepActionDto();
        action.setTag("P_VRAB");
        action.setValue(500);
        recipe.getSteps().get(0).setAction(List.of(action));
        recipe.getSteps().get(0).setCondition_script("return elapsedMs >= 2000;");
        recipe.getSteps().get(0).setTimeout_ms(600000L);

        RecipeResponseDto saved = store.create(recipe);
        RecipeResponseDto loaded = store.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getSteps().get(0).getAction().get(0).getTag()).isEqualTo("P_VRAB");
        assertThat(loaded.getSteps().get(0).getAction().get(0).getValue()).isEqualTo(500);
        assertThat(loaded.getSteps().get(0).getCondition_script()).isEqualTo("return elapsedMs >= 2000;");
        assertThat(loaded.getSteps().get(0).getTimeout_ms()).isEqualTo(600000L);
    }
}
