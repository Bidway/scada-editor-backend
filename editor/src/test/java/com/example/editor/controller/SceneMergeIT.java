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

    private String pumpAndValve(long sceneId, Long pumpId, String pumpScript,
                                Long valveId, String valveScript) {
        return "[{" + (pumpId == null ? "" : "\"id\":" + pumpId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"" + pumpScript + "\"}]},"
                + "{" + (valveId == null ? "" : "\"id\":" + valveId + ",")
                + "\"name\":\"Клапан\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"" + valveScript + "\"}]}]";
    }

    private String envelope(long sceneId, String components, Integer base, String kind) {
        return "{\"components\":" + components + ",\"scene_id\":" + sceneId
                + ",\"based_on_version\":" + base + ",\"save_kind\":\"" + kind + "\"}";
    }

    @Test
    void changesInDifferentComponents_areMerged() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, "a()", null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        // Чужое сохранение: правит клапан, версия становится base + 1.
        updateScene(sceneId, pumpAndValve(sceneId, pumpId, "a()", valveId, "ИХ()"), base);

        // Моё: правит насос и всё ещё думает, что текущая версия — base.
        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, "МОЁ()", valveId, "b()"),
                                base, "MANUAL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.get("merged")).isNotNull();
        assertThat(response.get("merged").get("base_version").asInt()).isEqualTo(base);
        assertThat(response.get("merged").get("changes")).isNotEmpty();

        // Версионная арифметика самого пути слияния: до финального ревью её не проверял ни один
        // тест — головная функция ветки подтверждалась только косвенно. Слияние обязано
        // дописывать версию поверх чужой (base + 1), а не поверх своей устаревшей базы, и
        // записанный снимок обязан описывать слитое состояние, а не присланное.
        assertThat(response.get("merged").get("merged_with_version").asInt()).isEqualTo(base + 1);
        assertThat(response.get("version_no").asInt())
                .as("слияние дописывает версию поверх чужой, а не поверх своей устаревшей базы")
                .isEqualTo(base + 2);
        assertThat(currentVersion(sceneId, "scenes"))
                .as("история сцены обязана кончаться той версией, что вернулась клиенту")
                .isEqualTo(base + 2);
        JsonNode recorded = versionContent(sceneId, "scenes", base + 2).get("children");
        assertThat(recorded.get(0).get("scripts").get(0).get("script").asText())
                .as("в снимок обязано лечь слитое состояние, а не присланное мной")
                .isEqualTo("МОЁ()");
        assertThat(recorded.get(1).get("scripts").get(0).get("script").asText())
                .isEqualTo("ИХ()");

        JsonNode scene = getComponent(sceneId);
        assertThat(scene.get("children").get(0).get("scripts").get(0).get("script").asText())
                .isEqualTo("МОЁ()");
        assertThat(scene.get("children").get(1).get("scripts").get(0).get("script").asText())
                .as("чужая правка обязана уцелеть — ради этого всё и делается")
                .isEqualTo("ИХ()");
    }

    /**
     * I-5 (найдено финальным ревью ветки): слияние, из которого не вышло ни одной строки отчёта.
     * Так бывает, когда обе стороны сделали одну и ту же правку — спорить не о чем, показывать
     * в {@code changes} нечего. Раньше {@code mergedReport} возвращал в этом случае {@code null},
     * и клиент видел обычный 200: ни того, что его база устарела, ни того, с какой версией он
     * слился. {@code base_version} и {@code merged_with_version} полезны сами по себе — блок
     * {@code merged} выдаётся всегда, когда слияние было, а пустой {@code changes} и означает
     * «разошлись, но одинаково».
     */
    @Test
    void mergeWithNothingToReport_stillTellsTheClientItMerged() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, "a()", null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        // Чужое сохранение и моё — правка в одну и ту же строку одним и тем же текстом.
        updateScene(sceneId, pumpAndValve(sceneId, pumpId, "ОБА()", valveId, "b()"), base);

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, "ОБА()", valveId, "b()"),
                                base, "MANUAL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertThat(response.hasNonNull("merged"))
                .as("клиент обязан узнать, что его база устарела, даже когда показывать нечего")
                .isTrue();
        assertThat(response.get("merged").get("base_version").asInt()).isEqualTo(base);
        assertThat(response.get("merged").get("merged_with_version").asInt())
                .as("с какой версией слились — половина смысла блока merged")
                .isEqualTo(base + 1);
        assertThat(response.get("merged").get("changes"))
                .as("обе стороны сделали одно и то же — строк отчёта нет, и это не повод молчать")
                .isEmpty();
        assertThat(response.get("version_no").asInt())
                .as("содержимое совпало с чужой версией — дедупликация по хешу отдаёт её же,"
                        + " новой версии в истории не появляется")
                .isEqualTo(base + 1);
    }

    @Test
    void changesInTheSameComponent_conflictAndWriteNothing() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, "a()", null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, pumpAndValve(sceneId, pumpId, "ИХ()", valveId, "b()"), base);

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, "МОЁ()", valveId, "b()"),
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
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, "a()", null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        updateScene(sceneId, pumpAndValve(sceneId, pumpId, "a()", valveId, "ИХ()"), base);

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, "МОЁ()", valveId, "b()"),
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
        JsonNode created = saveComponents(pumpAndValve(sceneId, null, "a()", null, "b()"));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(sceneId,
                                pumpAndValve(sceneId, pumpId, "МОЁ()", valveId, "b()"),
                                999, "MANUAL")))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("error").asText())
                .as("базы для слияния нет — слить не от чего, но это не 500")
                .isEqualTo("version_mismatch");
    }
}
