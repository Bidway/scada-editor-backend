package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Рецепт хранится файлом (RecipeFileStore), но REST-контракт по сути тот же — с точностью до
 * имени поля (property_name вместо row_name) и типа id (строка-слаг вместо счётчика). Здесь —
 * только две точки реального риска регрессии; остальное проверяется вручную.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecipeSaveIT extends EditorApiTestSupport {

    private long componentWithProperty() throws Exception {
        long sceneId = newScene();
        JsonNode component = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}]}]").get(0);
        return component.get("id").asLong();
    }

    private String createRecipe(long componentId, String valuesJson) throws Exception {
        String body = mockMvc.perform(post("/api/editor/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private JsonNode getRecipe(String recipeId) throws Exception {
        String body = mockMvc.perform(get("/api/editor/recipes/" + recipeId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void create_thenRename_keepsIdStable() throws Exception {
        long componentId = componentWithProperty();
        String recipeId = createRecipe(componentId,
                "[{\"property_name\":\"Уставка\",\"value\":\"10\"}]");

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия Б\",\"component_id\":" + componentId
                                + ",\"values\":[{\"property_name\":\"Уставка\",\"value\":\"10\"}]}"))
                .andExpect(status().isOk());

        JsonNode after = getRecipe(recipeId);
        assertThat(after.get("id").asText()).isEqualTo(recipeId);
        assertThat(after.get("name").asText()).isEqualTo("Партия Б");
    }

    /**
     * Историческая регрессия (scada-m2n): отсутствие поля values и пустой массив values должны
     * различаться — «не трогать» и «стереть всё» соответственно.
     */
    @Test
    void missingValuesField_keepsValues_butEmptyArray_erasesThem() throws Exception {
        long componentId = componentWithProperty();
        String recipeId = createRecipe(componentId,
                "[{\"property_name\":\"Уставка\",\"value\":\"10\"}]");

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId + "}"))
                .andExpect(status().isOk());
        assertThat(getRecipe(recipeId).get("values")).hasSize(1);

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":[]}"))
                .andExpect(status().isOk());
        assertThat(getRecipe(recipeId).get("values")).isEmpty();
    }
}
