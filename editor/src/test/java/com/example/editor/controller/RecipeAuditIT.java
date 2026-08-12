package com.example.editor.controller;

import com.example.editor.model.recipe.RecipeChange;
import com.example.editor.model.recipe.RecipeChangeType;
import com.example.editor.repository.recipe.RecipeChangeRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * След изменений наборов значений. До этой работы правка уставки не оставляла никакого следа:
 * RecipeServiceImpl шёл мимо Command Pattern, и команд для рецептов не существовало. При этом
 * значение набора — то, что уходит в ПЛК, и вопрос «кто поменял уставку с 10 на 42» здесь
 * важнее, чем для чего-либо ещё в editor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecipeAuditIT extends EditorApiTestSupport {

    @Autowired
    private RecipeChangeRepository recipeChangeRepository;

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
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия A\",\"component_id\":" + componentId
                                + ",\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void updateRecipe(long recipeId, long componentId, String name, String valuesJson)
            throws Exception {
        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"component_id\":" + componentId
                                + ",\"values\":" + valuesJson + "}"))
                .andExpect(status().isOk());
    }

    private List<RecipeChange> valueChanges(long recipeId) {
        return recipeChangeRepository.findByRecipeIdOrderByIdAsc(recipeId).stream()
                .filter(c -> c.getChangeType() == RecipeChangeType.VALUE)
                .toList();
    }

    private static final String TWO_VALUES =
            "[{\"row_name\":\"Уставка\",\"value\":\"10\"},"
            + "{\"row_name\":\"Режим\",\"value\":\"1\"}]";

    @Test
    void changingOneValue_recordsOldAndNew() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        updateRecipe(recipeId, componentId, "Партия A",
                "[{\"row_name\":\"Уставка\",\"value\":\"42\"},"
                + "{\"row_name\":\"Режим\",\"value\":\"1\"}]");

        List<RecipeChange> afterUpdate = valueChanges(recipeId).stream()
                .filter(c -> "Уставка".equals(c.getRowName()) && "42".equals(c.getNewValue()))
                .toList();

        assertThat(afterUpdate).hasSize(1);
        RecipeChange change = afterUpdate.get(0);
        assertThat(change.getOldValue()).isEqualTo("10");
        assertThat(change.getUserName()).isEqualTo(USER);
        assertThat(change.getComponentId()).isEqualTo(componentId);
        assertThat(change.getChangedAt()).isNotNull();
    }

    /**
     * Главное свойство этой истории: она пишется по факту изменения, а не по факту
     * сохранения. Иначе каждое сохранение набора плодило бы строки на все значения, и
     * найти в этом настоящую правку уставки стало бы невозможно.
     */
    @Test
    void savingWithoutChanges_recordsNothingNew() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);
        int before = valueChanges(recipeId).size();

        updateRecipe(recipeId, componentId, "Партия A", TWO_VALUES);

        assertThat(valueChanges(recipeId)).hasSize(before);
    }

    @Test
    void addingValue_recordsWithoutOldValue() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId,
                "[{\"row_name\":\"Уставка\",\"value\":\"10\"}]");

        updateRecipe(recipeId, componentId, "Партия A", TWO_VALUES);

        List<RecipeChange> added = valueChanges(recipeId).stream()
                .filter(c -> "Режим".equals(c.getRowName()))
                .toList();

        assertThat(added).hasSize(1);
        assertThat(added.get(0).getOldValue()).isNull();
        assertThat(added.get(0).getNewValue()).isEqualTo("1");
    }

    @Test
    void removingValue_recordsWithoutNewValue() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        updateRecipe(recipeId, componentId, "Партия A",
                "[{\"row_name\":\"Уставка\",\"value\":\"10\"}]");

        List<RecipeChange> removed = valueChanges(recipeId).stream()
                .filter(c -> "Режим".equals(c.getRowName()) && c.getNewValue() == null)
                .toList();

        assertThat(removed).hasSize(1);
        assertThat(removed.get(0).getOldValue()).isEqualTo("1");
    }

    private List<RecipeChange> changesOfType(long recipeId, RecipeChangeType type) {
        return recipeChangeRepository.findByRecipeIdOrderByIdAsc(recipeId).stream()
                .filter(c -> c.getChangeType() == type)
                .toList();
    }

    @Test
    void creatingRecipe_recordsCreate() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        List<RecipeChange> created = changesOfType(recipeId, RecipeChangeType.CREATE);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getNewValue()).isEqualTo("Партия A");
        assertThat(created.get(0).getUserName()).isEqualTo(USER);
        assertThat(created.get(0).getRowName()).isNull();
        // Лента сортируется по id: создание набора обязано идти раньше значений, с которыми
        // он создан. Порядок держится только порядком элементов в списке changes — без этой
        // проверки его молча вернёт назад любая перестановка строк в create().
        assertThat(valueChanges(recipeId)).allSatisfy(value ->
                assertThat(value.getId())
                        .as("значение %s записано после CREATE", value.getRowName())
                        .isGreaterThan(created.get(0).getId()));
    }

    @Test
    void renamingRecipe_recordsOldAndNewName() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        updateRecipe(recipeId, componentId, "Партия Б", TWO_VALUES);

        List<RecipeChange> renamed = changesOfType(recipeId, RecipeChangeType.RENAME);

        assertThat(renamed).hasSize(1);
        assertThat(renamed.get(0).getOldValue()).isEqualTo("Партия A");
        assertThat(renamed.get(0).getNewValue()).isEqualTo("Партия Б");
    }

    @Test
    void savingWithSameName_recordsNoRename() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        updateRecipe(recipeId, componentId, "Партия A", TWO_VALUES);

        assertThat(changesOfType(recipeId, RecipeChangeType.RENAME)).isEmpty();
    }

    /**
     * История обязана пережить удаление набора — иначе на вопрос «кто удалил» ответить
     * будет некому. Поэтому у recipe_change нет внешнего ключа на recipe.
     */
    @Test
    void deletingRecipe_recordsDeleteAndHistorySurvives() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);

        mockMvc.perform(delete("/api/editor/recipes/" + recipeId)
                        .header("X-Username", USER))
                .andExpect(status().isOk());

        assertThat(changesOfType(recipeId, RecipeChangeType.DELETE)).hasSize(1);
        assertThat(changesOfType(recipeId, RecipeChangeType.CREATE))
                .as("запись о создании набора должна пережить его удаление")
                .hasSize(1);
        assertThat(valueChanges(recipeId))
                .as("записи о значениях набора должны пережить его удаление")
                .hasSize(2);
    }

    /**
     * Поле values не прислано — значения не тронуты (scada-m2n), значит и в историю писать
     * нечего. Переименование тем же запросом при этом фиксируется: без этой проверки тест
     * прошёл бы и на коде, который просто ничего не пишет по такому запросу вовсе.
     */
    @Test
    void savingWithoutValuesField_recordsNoValueChanges() throws Exception {
        long componentId = componentWithTwoRows();
        long recipeId = createRecipe(componentId, TWO_VALUES);
        int before = valueChanges(recipeId).size();

        mockMvc.perform(put("/api/editor/recipes/" + recipeId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Партия Б\",\"component_id\":" + componentId + "}"))
                .andExpect(status().isOk());

        assertThat(valueChanges(recipeId)).hasSize(before);
        assertThat(changesOfType(recipeId, RecipeChangeType.RENAME)).hasSize(1);
    }
}
