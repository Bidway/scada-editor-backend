package com.example.editor.controller;

import com.example.editor.model.recipe.RecipeValue;
import com.example.editor.repository.recipe.RecipeValueRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правка свойства — такое же изменение сцены, как правка компонента, и обязана и проверять
 * версию, и оставлять снимок. До перехода на версии единственным следом этих трёх эндпоинтов
 * был command_log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PropertyVersionIT extends EditorApiTestSupport {

    @Autowired
    private RecipeValueRepository recipeValueRepository;

    @Test
    @DisplayName("Создание свойства пишет новую версию сцены")
    void create_recordsSceneVersion() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        int before = currentVersion(sceneId, "scenes");

        mockMvc.perform(post("/api/editor/properties")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, "speed", before)))
                .andExpect(status().isOk());

        assertEquals(before + 1, currentVersion(sceneId, "scenes"));
    }

    @Test
    @DisplayName("Создание без based_on_version — 400, когда у сцены версия уже есть")
    void create_withoutBaseVersion_isRejected() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);

        mockMvc.perform(post("/api/editor/properties")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, "speed", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Создание с устаревшим based_on_version — 409 version_mismatch")
    void create_withStaleBaseVersion_conflicts() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        int current = currentVersion(sceneId, "scenes");

        mockMvc.perform(post("/api/editor/properties")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, "speed", current - 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("version_mismatch"))
                .andExpect(jsonPath("$.current_version").value(current));
    }

    @Test
    @DisplayName("Правка свойства пишет версию сцены")
    void update_recordsSceneVersion() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        long propertyId = createProperty(componentId, "speed", currentVersion(sceneId, "scenes"));
        int before = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/properties/" + propertyId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, "velocity", before)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("velocity"));

        assertEquals(before + 1, currentVersion(sceneId, "scenes"));
    }

    /**
     * Точечная правка — единственное место, где переименование строки набора отличимо от пары
     * «удалили строку, добавили другую», и потому единственное, откуда зовётся
     * {@code RecipeValueRepository.renameRow}. Без набора с этим именем ветка {@code moved > 0}
     * не исполняется вовсе: тест, проверяющий только номер версии, зелен и на коде, где переноса
     * нет.
     */
    @Test
    @DisplayName("Переименование свойства переносит значения наборов на новое имя строки")
    void update_renamingProperty_movesRecipeValues() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        long propertyId = createProperty(componentId, "speed", currentVersion(sceneId, "scenes"));
        long recipeId = createRecipe(componentId, "speed", "10");
        int before = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/properties/" + propertyId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, "velocity", before)))
                .andExpect(status().isOk());

        assertThat(valuesOf(recipeId))
                .as("значение набора обязано переехать на новое имя строки, а не осиротеть")
                .singleElement()
                .satisfies(value -> {
                    assertEquals("velocity", value.getRowName());
                    assertEquals("10", value.getValue());
                });
        assertEquals(before + 1, currentVersion(sceneId, "scenes"));
    }

    /**
     * Перенос свойства на компонент другой сцены отвергается. {@code updateEntity} маппит
     * {@code component} из {@code component_id}, а сцена для гарда и снимка берётся по прежнему
     * компоненту — сцена-приёмник получала бы чужое свойство без проверки версии и без записи в
     * историю. С уходом {@code command_log} следа от такой правки не оставалось бы вовсе.
     */
    @Test
    @DisplayName("Перенос свойства на компонент другой сцены — 400, обе сцены не тронуты")
    void update_toComponentOfAnotherScene_isRejected() throws Exception {
        long sceneA = newScene();
        long componentA = componentInScene(sceneA);
        long propertyId = createProperty(componentA, "speed", currentVersion(sceneA, "scenes"));
        long sceneB = newScene();
        long componentB = componentInScene(sceneB);
        int baseA = currentVersion(sceneA, "scenes");
        int baseB = currentVersion(sceneB, "scenes");

        mockMvc.perform(put("/api/editor/properties/" + propertyId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentB, "speed", baseA)))
                .andExpect(status().isBadRequest());

        assertEquals(propertyId, propertyId(getComponent(componentA), "speed"),
                "свойство обязано остаться у своего компонента");
        assertThat(getComponent(componentB).get("properties"))
                .as("чужой компонент правится только своим запросом — со своим гардом версии")
                .isEmpty();
        assertEquals(baseA, currentVersion(sceneA, "scenes"));
        assertEquals(baseB, currentVersion(sceneB, "scenes"));
    }

    @Test
    @DisplayName("Удаление свойства пишет версию; номер едет в query")
    void delete_recordsSceneVersion() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        long propertyId = createProperty(componentId, "speed", currentVersion(sceneId, "scenes"));
        int before = currentVersion(sceneId, "scenes");

        mockMvc.perform(delete("/api/editor/properties/" + propertyId)
                        .header("X-Username", USER)
                        .param("based_on_version", String.valueOf(before)))
                .andExpect(status().isOk());

        assertEquals(before + 1, currentVersion(sceneId, "scenes"));
    }

    @Test
    @DisplayName("Удаление с устаревшим номером — 409, свойство остаётся на месте")
    void delete_withStaleBaseVersion_conflicts() throws Exception {
        long sceneId = newScene();
        long componentId = componentInScene(sceneId);
        long propertyId = createProperty(componentId, "speed", currentVersion(sceneId, "scenes"));
        int current = currentVersion(sceneId, "scenes");

        mockMvc.perform(delete("/api/editor/properties/" + propertyId)
                        .header("X-Username", USER)
                        .param("based_on_version", String.valueOf(current - 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("version_mismatch"));

        assertEquals(current, currentVersion(sceneId, "scenes"));
    }

    // --- хелперы ---

    /**
     * Компонент под сценой; сцена после этого гарантированно имеет хотя бы одну версию.
     * {@code saveComponents} отдаёт уже развёрнутый массив components из конверта ответа.
     */
    private long componentInScene(long sceneId) throws Exception {
        JsonNode saved = saveComponents("""
                [{"name": "pump", "type": "group", "parent_id": %d}]
                """.formatted(sceneId));
        return saved.get(0).get("id").asLong();
    }

    private long createProperty(long componentId, String name, Integer basedOnVersion)
            throws Exception {
        String body = mockMvc.perform(post("/api/editor/properties")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(propertyJson(componentId, name, basedOnVersion)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Набор с одной строкой, привязанной к свойству по имени: {@code row_name} — это и есть имя. */
    private long createRecipe(long componentId, String rowName, String value) throws Exception {
        String body = mockMvc.perform(post("/api/editor/recipes")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Партия A", "component_id": %d,
                                 "values": [{"row_name": "%s", "value": "%s"}]}
                                """.formatted(componentId, rowName, value)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private List<RecipeValue> valuesOf(long recipeId) {
        return recipeValueRepository.findAll().stream()
                .filter(v -> v.getRecipe() != null && recipeId == v.getRecipe().getId())
                .toList();
    }

    /**
     * {@code property_type} прислан всегда: колонка {@code NOT NULL}
     * ({@link com.example.editor.model.component.ComponentProperty#getPropertyType()}), и без
     * неё запрос падает на констрейнте БД раньше, чем дойдёт до гарда версии, — этот тест не
     * про то.
     */
    private String propertyJson(long componentId, String name, Integer basedOnVersion) {
        String base = basedOnVersion == null
                ? ""
                : ", \"based_on_version\": " + basedOnVersion;
        return """
                {"component_id": %d, "name": "%s", "value_type": "NUMBER", "property_type": "Тег"%s}
                """.formatted(componentId, name, base);
    }

}
