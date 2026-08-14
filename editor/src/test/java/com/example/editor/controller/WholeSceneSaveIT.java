package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT} несёт сцену целиком: отсутствие компонента в теле означает его удаление.
 * <p>
 * Без этого слияние не может отличить «я удалил» от «я не прислал», и половина конфликтов из
 * контракта (§2) невыразима. Цена — {@code scene_id} в конверте: по пустому массиву иначе не
 * понять, чьи это дети, а «стереть не ту сцену» — самая дорогая ошибка из возможных здесь.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WholeSceneSaveIT extends EditorApiTestSupport {

    private String twoComponents(long sceneId) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + "},"
                + "{\"name\":\"Клапан\",\"type\":\"valve\",\"parent_id\":" + sceneId + "}]";
    }

    @Test
    void componentMissingFromThePut_isDeleted() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(twoComponents(sceneId));
        long pumpId = created.get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, "[{\"id\":" + pumpId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "}]", base);

        assertThat(getComponent(sceneId).get("children"))
                .as("прислали сцену целиком — чего в ней нет, того нет и в базе")
                .hasSize(1);
    }

    @Test
    void putWithoutSceneId_isRejected() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"based_on_version\":" + base
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyPutWithSceneId_clearsTheScene() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, "[]", base);

        assertThat(getComponent(sceneId).get("children")).isEmpty();
    }
}
