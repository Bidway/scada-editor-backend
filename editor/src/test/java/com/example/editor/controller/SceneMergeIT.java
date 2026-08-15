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
 * Слияние на проводе. Через контейнер проверяется только то, что нельзя проверить юнит-тестом:
 * формы ответов, отсутствие записанных данных после конфликта и поведение видов сохранения.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneMergeIT extends EditorApiTestSupport {

    /**
     * Id скриптов передаются явно (как их вернуло бы предыдущее чтение сцены реальным фронтом):
     * слияние сопоставляет вложенные строки сперва по id и лишь при его отсутствии — по имени, а
     * «моя» сторона здесь всегда собрана из живого ответа сервера, а не с нуля. Без id и «моя», и
     * «чужая», и базовая версия одного скрипта попадали бы в разные ключи (id-ключ у слепка,
     * name-ключ у меня) и слияние решило бы, что я эту строку удалил, хотя я её просто не менял.
     */
    private String pumpAndValve(long sceneId, Long pumpId, Long pumpScriptId, String pumpScript,
                                Long valveId, Long valveScriptId, String valveScript) {
        return "[{" + (pumpId == null ? "" : "\"id\":" + pumpId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{" + (pumpScriptId == null ? "" : "\"id\":" + pumpScriptId + ",")
                + "\"name\":\"Открыть\",\"script\":\"" + pumpScript + "\"}]},"
                + "{" + (valveId == null ? "" : "\"id\":" + valveId + ",")
                + "\"name\":\"Клапан\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{" + (valveScriptId == null ? "" : "\"id\":" + valveScriptId + ",")
                + "\"name\":\"Открыть\",\"script\":\"" + valveScript + "\"}]}]";
    }

    private String envelope(long sceneId, String components, Integer base, String kind) {
        return "{\"components\":" + components + ",\"scene_id\":" + sceneId
                + ",\"based_on_version\":" + base + ",\"save_kind\":\"" + kind + "\"}";
    }

    @Test
    void changesInDifferentComponents_areMerged() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, null, "a()", null, null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long pumpScriptId = created.get(0).get("scripts").get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        long valveScriptId = created.get(1).get("scripts").get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        // Чужое сохранение: правит клапан, версия становится base + 1.
        updateScene(sceneId, pumpAndValve(sceneId, pumpId, pumpScriptId, "a()",
                valveId, valveScriptId, "ИХ()"), base);

        // Моё: правит насос и всё ещё думает, что текущая версия — base.
        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, pumpScriptId, "МОЁ()",
                                        valveId, valveScriptId, "b()"),
                                base, "MANUAL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("merged")).isNotNull();
        assertThat(response.get("merged").get("base_version").asInt()).isEqualTo(base);
        assertThat(response.get("merged").get("changes")).isNotEmpty();

        JsonNode scene = getComponent(sceneId);
        assertThat(scene.get("children").get(0).get("scripts").get(0).get("script").asText())
                .isEqualTo("МОЁ()");
        assertThat(scene.get("children").get(1).get("scripts").get(0).get("script").asText())
                .as("чужая правка обязана уцелеть — ради этого всё и делается")
                .isEqualTo("ИХ()");
    }

    @Test
    void changesInTheSameComponent_conflictAndWriteNothing() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, null, "a()", null, null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long pumpScriptId = created.get(0).get("scripts").get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        long valveScriptId = created.get(1).get("scripts").get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, pumpAndValve(sceneId, pumpId, pumpScriptId, "ИХ()",
                valveId, valveScriptId, "b()"), base);

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, pumpScriptId, "МОЁ()",
                                        valveId, valveScriptId, "b()"),
                                base, "MANUAL")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("error").asText()).isEqualTo("merge_conflict");
        assertThat(response.get("base_version").asInt()).isEqualTo(base);
        assertThat(response.get("conflicts")).isNotEmpty();
        assertThat(response.get("conflicts").get(0).get("path").asText()).contains("Насос");

        assertThat(getComponent(sceneId).get("children").get(0)
                .get("scripts").get(0).get("script").asText())
                .as("конфликт не оставляет записанных данных")
                .isEqualTo("ИХ()");
    }

    @Test
    void autosaveDoesNotMerge() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, null, "a()", null, null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long pumpScriptId = created.get(0).get("scripts").get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        long valveScriptId = created.get(1).get("scripts").get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, pumpAndValve(sceneId, pumpId, pumpScriptId, "a()",
                valveId, valveScriptId, "ИХ()"), base);

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, pumpScriptId, "МОЁ()",
                                        valveId, valveScriptId, "b()"),
                                base, "AUTOSAVE")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("error").asText())
                .as("сливать без человека перед экраном нельзя — блок merged некому прочитать")
                .isEqualTo("version_mismatch");
    }

    @Test
    void missingBaseVersion_answers409() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, null, "a()", null, null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long pumpScriptId = created.get(0).get("scripts").get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        long valveScriptId = created.get(1).get("scripts").get(0).get("id").asLong();

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, pumpScriptId, "МОЁ()",
                                        valveId, valveScriptId, "b()"),
                                999, "MANUAL")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("error").asText())
                .as("базы для слияния нет — слить не от чего, но это не 500")
                .isEqualTo("version_mismatch");
    }
}
