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
    void renameAndReuseOfTheFreedName_isRejectedWithExplanation() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        // Переименование по id и создание нового скрипта под освободившимся именем — одним
        // запросом. Поддержать это нельзя: Hibernate на flush выполняет INSERT раньше UPDATE,
        // и вставка новой строки бьётся о UNIQUE (component_id, name) раньше, чем
        // переименование освободит имя. Отвергаем до записи и объясняем, что делать.
        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":" + componentId + ","
                                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                                + "\"scripts\":[{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                                + "\"script\":\"return 1;\"},"
                                + "{\"name\":\"Открыть\",\"script\":\"return 2;\"}],"
                                + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]}]"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("ошибка обязана объяснять, что делать, а не показывать констрейнт Postgres")
                .contains("Открыть")
                .doesNotContain("scripts_uk");
    }

    @Test
    void plainRenameById_stillWorks() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        // Переименование без занятия освободившегося имени — обычный случай, он проходить обязан.
        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Закрыть", originalScriptId, "Норма", null)).get(0);

        assertThat(updated.get("scripts")).hasSize(1);
        assertThat(scriptId(updated, "Закрыть")).isEqualTo(originalScriptId);
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

    @Test
    void nameEntryDoesNotStealRowClaimedById() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        // Обратный порядок относительно renameAndReuseOfTheFreedName_isRejectedWithExplanation:
        // элемент без id идёт первым. Без двухпроходного разрешения оба элемента находят одну и
        // ту же строку по id (карта имён чистится только когда элемент С id обработан, а он ещё
        // не наступил) — второй set молча затирает первый, и скрипт "Открыть" пропадает без
        // ошибки. После правки id разбирается первым проходом, элемент без id получает новую
        // строку под именем "Открыть", которое как раз освобождает переименование — это ловит
        // rejectNameSwaps.
        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":" + componentId + ","
                                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"},"
                                + "{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                                + "\"script\":\"return 2;\"}],"
                                + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]}]"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("явный id не должен молча съедать элемент без id того же имени")
                .contains("Открыть")
                .doesNotContain("scripts_uk");
    }

    @Test
    void sameIdTwice_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":" + componentId + ","
                                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                                + "\"scripts\":[{\"id\":" + originalScriptId + ",\"name\":\"Открыть\","
                                + "\"script\":\"return 1;\"},"
                                + "{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                                + "\"script\":\"return 2;\"}],"
                                + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]}]"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("один и тот же id адресован дважды в одном запросе — неоднозначность, "
                        + "а не переименование")
                .contains("addressed twice");
    }

    private String withBinding(long sceneId, Long componentId, String bindingName, Long bindingId) {
        return "[{" + (componentId == null ? "" : "\"id\":" + componentId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"bindings\":[{" + (bindingId == null ? "" : "\"id\":" + bindingId + ",")
                + "\"component_property_name\":\"Уставка\",\"name\":\"" + bindingName + "\","
                + "\"script\":\"{}\"}]}]";
    }

    private long bindingId(JsonNode component, String name) {
        for (JsonNode b : component.get("bindings")) {
            if (name.equals(b.get("name").asText())) {
                return b.get("id").asLong();
            }
        }
        throw new AssertionError("Нет биндинга '" + name + "' в " + component);
    }

    @Test
    void resavingBinding_keepsItsId() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(withBinding(sceneId, null, "цвет", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalBindingId = bindingId(created, "цвет");

        JsonNode updated = updateComponents(
                withBinding(sceneId, componentId, "цвет", null)).get(0);

        assertThat(bindingId(updated, "цвет"))
                .as("пересохранение без правок не должно менять id биндинга (scada-dna)")
                .isEqualTo(originalBindingId);
    }

    @Test
    void renamingBindingById_keepsItsId() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(withBinding(sceneId, null, "цвет", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalBindingId = bindingId(created, "цвет");

        JsonNode updated = updateComponents(
                withBinding(sceneId, componentId, "заливка", originalBindingId)).get(0);

        assertThat(updated.get("bindings")).hasSize(1);
        assertThat(bindingId(updated, "заливка")).isEqualTo(originalBindingId);
    }
}
