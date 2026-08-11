package com.example.editor.controller;

import com.example.editor.model.recipe.RecipeValue;
import com.example.editor.repository.recipe.RecipeValueRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Значение набора адресует строку по имени: тега у строки может не быть вовсе, а номер
 * сдвигается при вставке строки в середину. Правка одной уставки не должна переписывать
 * весь набор — до сопоставления по row_name здесь стоял clear() + insert.
 * <p>
 * Ссылок по RecipeValue.id в контуре нет, поэтому прежний способ падений не давал. Тест
 * сторожит не корректность, а отсутствие лишней записи в базу при правке одной строки.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecipeSaveIT extends EditorApiTestSupport {

    @Autowired
    private RecipeValueRepository recipeValueRepository;

    private long componentWithTwoRows() throws Exception {
        long sceneId = newScene();
        JsonNode component = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"},"
                + "{\"name\":\"Режим\",\"value_type\":\"int\",\"property_type\":\"Тег\"}]}]").get(0);
        return component.get("id").asLong();
    }

    private long createRecipe(long componentId, String valuesJson) throws Exception {
        String body = mockMvc.perform(post("/api/editor/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** id значений набора по имени строки — через репозиторий, в ответе API их нет. */
    private Map<String, Long> valueIds(long recipeId) {
        Map<String, Long> ids = new java.util.HashMap<>();
        for (RecipeValue v : recipeValueRepository.findAll()) {
            if (v.getRecipe() != null && recipeId == v.getRecipe().getId()) {
                ids.put(v.getRowName(), v.getId());
            }
        }
        return ids;
    }

    @Test
    void updatingOneValue_keepsOtherValueId() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId,
                "[{\"row_name\":\"Уставка\",\"value\":\"10\"},"
                + "{\"row_name\":\"Режим\",\"value\":\"1\"}]");

        Map<String, Long> before = valueIds(recipeId);
        assertThat(before).containsKeys("Уставка", "Режим");

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":[{\"row_name\":\"Уставка\",\"value\":\"42\"},"
                                + "{\"row_name\":\"Режим\",\"value\":\"1\"}]}"))
                .andExpect(status().isOk());

        Map<String, Long> after = valueIds(recipeId);
        assertThat(after.get("Режим")).isEqualTo(before.get("Режим"));
        assertThat(after.get("Уставка")).isEqualTo(before.get("Уставка"));
        assertThat(after).hasSize(2);
    }

    @Test
    void updatingRecipe_dropsValuesMissingFromPayload() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId,
                "[{\"row_name\":\"Уставка\",\"value\":\"10\"},"
                + "{\"row_name\":\"Режим\",\"value\":\"1\"}]");

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":[{\"row_name\":\"Уставка\",\"value\":\"10\"}]}"))
                .andExpect(status().isOk());

        assertThat(valueIds(recipeId)).containsOnlyKeys("Уставка");
    }

    /**
     * Резолв берёт строку по имени, поэтому второе значение на ту же строку осталось бы
     * недостижимым — такой набор отвергается на входе.
     */
    @Test
    void duplicateRowInRecipe_isRejected() throws Exception {
        long componentId = componentWithTwoRows();

        mockMvc.perform(post("/api/editor/recipes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия B\",\"component_id\":" + componentId
                                + ",\"values\":[{\"row_name\":\"Уставка\",\"value\":\"1\"},"
                                + "{\"row_name\":\"Уставка\",\"value\":\"2\"}]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Строка, которой в таблице нет, попадает в unmatched_rows, а не теряется молча:
     * пропажа уставки иначе видна только по поведению установки.
     */
    @Test
    void resolve_reportsUnmatchedRows() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId,
                "[{\"row_name\":\"Уставка\",\"value\":\"10\"},"
                + "{\"row_name\":\"Нет такой\",\"value\":\"5\"}]");

        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/editor/recipes/" + recipeId + "/resolved"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode resolved = objectMapper.readTree(body);
        assertThat(resolved.get("unmatched_rows").toString()).contains("Нет такой");
        assertThat(resolved.get("values")).hasSize(1);
    }
}
