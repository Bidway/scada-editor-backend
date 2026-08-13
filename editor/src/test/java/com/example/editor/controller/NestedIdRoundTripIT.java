package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Переименование вложенной сущности обязано сохранять её id.
 * <p>
 * До появления {@code id} в DTO сопоставление шло только по имени, и переименование было
 * неотличимо от «удалили одну, создали другую»: строка пересоздавалась с новым id. Для слияния
 * это значит ложный конфликт на каждом переименовании, для мониторинга — обесценивание
 * {@code scriptId} в уже открытых сессиях.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NestedIdRoundTripIT extends EditorApiTestSupport {

    private String componentJson(long sceneId, Long componentId, String scriptName,
                                 Long scriptId, String stateName, Long stateId) {
        return "[{" + (componentId == null ? "" : "\"id\":" + componentId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{" + (scriptId == null ? "" : "\"id\":" + scriptId + ",")
                + "\"name\":\"" + scriptName + "\",\"script\":\"return 1;\"}],"
                + "\"states\":[{" + (stateId == null ? "" : "\"id\":" + stateId + ",")
                + "\"name\":\"" + stateName + "\",\"image\":{},\"isDefault\":true}]"
                + "}]";
    }

    private long stateId(JsonNode component, String name) {
        for (JsonNode s : component.get("states")) {
            if (name.equals(s.get("name").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError("Нет состояния '" + name + "' в " + component);
    }

    @Test
    void renamingScriptById_keepsItsId() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть клапан", originalScriptId, "Норма", null)).get(0);

        assertThat(updated.get("scripts")).hasSize(1);
        assertThat(scriptId(updated, "Открыть клапан"))
                .as("прислали id — это переименование, а не новая строка")
                .isEqualTo(originalScriptId);
    }

    @Test
    void renamingStateById_keepsItsId() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalStateId = stateId(created, "Норма");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть", null, "Нормальное", originalStateId)).get(0);

        assertThat(updated.get("states")).hasSize(1);
        assertThat(stateId(updated, "Нормальное")).isEqualTo(originalStateId);
    }

    @Test
    void withoutId_matchingStillFallsBackToName() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть", null, "Норма", null)).get(0);

        assertThat(scriptId(updated, "Открыть"))
                .as("id не прислали — сопоставление по имени работает как раньше")
                .isEqualTo(originalScriptId);
    }
}
