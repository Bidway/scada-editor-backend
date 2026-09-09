package com.example.editor.service.Impl;

import com.example.editor.dto.recipe.RecipeCreateDto;
import com.example.editor.dto.recipe.RecipeStepActionDto;
import com.example.editor.dto.recipe.RecipeStepDto;
import com.example.editor.dto.recipe.RecipeTagDto;
import com.example.editor.repository.recipe.RecipeFileStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeServiceImplTest {

    private RecipeServiceImpl service(Path dir) {
        return new RecipeServiceImpl(new RecipeFileStore(dir.toString(), new ObjectMapper()));
    }

    private RecipeTagDto tag(String name, String valueType) {
        RecipeTagDto tag = new RecipeTagDto();
        tag.setName(name);
        tag.setTag("LINE1.PARAMS." + name);
        tag.setValue_type(valueType);
        return tag;
    }

    private RecipeCreateDto recipeWithAction(String tagName, Object value) {
        RecipeStepActionDto action = new RecipeStepActionDto();
        action.setTag(tagName);
        action.setValue(value);
        RecipeStepDto step = new RecipeStepDto();
        step.setName("Шаг 1");
        step.setAction(List.of(action));

        RecipeCreateDto dto = new RecipeCreateDto();
        dto.setName("Тест");
        dto.setTags(List.of(tag("P_VRAB", "number")));
        dto.setSteps(List.of(step));
        return dto;
    }

    @Test
    void create_rejectsActionReferencingUnknownTag(@TempDir Path dir) {
        RecipeServiceImpl service = service(dir);
        RecipeCreateDto dto = recipeWithAction("NOT_IN_MANIFEST", 500);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_IN_MANIFEST");
    }

    @Test
    void create_rejectsValueTypeMismatch(@TempDir Path dir) {
        RecipeServiceImpl service = service(dir);
        RecipeCreateDto dto = recipeWithAction("P_VRAB", true);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P_VRAB");
    }

    @Test
    void create_acceptsMatchingAction(@TempDir Path dir) {
        RecipeServiceImpl service = service(dir);
        RecipeCreateDto dto = recipeWithAction("P_VRAB", 500);

        assertThat(service.create(dto).getId()).isNotBlank();
    }
}
