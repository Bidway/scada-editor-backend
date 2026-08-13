package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Восстановление версии, в которой есть строка, удалённая после снимка.
 * <p>
 * Снимок сцены — это сериализованный ответ API, и вложенные {@code scripts[].id},
 * {@code states[].id}, {@code events[].id}, {@code bindings[].id} лежали в нём всегда. Пока у
 * DTO сохранения не было поля {@code id}, Jackson выбрасывал эти номера молча, и восстановление
 * сопоставляло вложенные сущности по имени. С появлением {@code id} в DTO из снимка стали
 * приезжать <b>исторические</b> номера — в том числе тех строк, которых в базе уже нет: аплаер
 * не находит их среди строк компонента и валит всё восстановление в 400.
 * <p>
 * Отсюда правило: id из снимка — подсказка, а не требование. Нет такой строки — сущность
 * создаётся заново (свой прежний номер она не вернёт), но восстановление обязано пройти.
 * Симметрично тому, что {@code dropMissingIds} и так делал для компонентов.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneRestoreDeletedRowsIT extends EditorApiTestSupport {

    private JsonNode restore(long sceneId, int versionNo) throws Exception {
        String body = mockMvc.perform(post("/api/editor/scenes/" + sceneId + "/restore/" + versionNo)
                        .header("X-Username", USER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String pump(long sceneId, Long componentId, String nested) {
        return "[{" + (componentId == null ? "" : "\"id\":" + componentId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + nested + "}]";
    }

    private JsonNode names(JsonNode component, String collection) {
        return component.get(collection);
    }

    @Test
    void restoringVersionWithDeletedScript_recreatesIt() throws Exception {
        long sceneId = newScene();
        String both = "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"},"
                + "{\"name\":\"Закрыть\",\"script\":\"return 2;\"}]";
        JsonNode created = saveComponents(pump(sceneId, null, both)).get(0);
        long componentId = created.get("id").asLong();

        updateComponents(pump(sceneId, componentId,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(names(getComponent(componentId), "scripts"))
                .as("скрипт, удалённый после снимка, обязан вернуться")
                .hasSize(2);
    }

    @Test
    void restoringVersionWithDeletedState_recreatesIt() throws Exception {
        long sceneId = newScene();
        String both = "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true},"
                + "{\"name\":\"Авария\",\"image\":{},\"isDefault\":false}]";
        JsonNode created = saveComponents(pump(sceneId, null, both)).get(0);
        long componentId = created.get("id").asLong();

        updateComponents(pump(sceneId, componentId,
                "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(names(getComponent(componentId), "states"))
                .as("состояние, удалённое после снимка, обязано вернуться")
                .hasSize(2);
    }

    @Test
    void restoringVersionWithDeletedEvent_recreatesIt() throws Exception {
        long sceneId = newScene();
        String both = "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"},"
                + "{\"event_type\":\"onHover\",\"script\":\"b()\"}]";
        JsonNode created = saveComponents(pump(sceneId, null, both)).get(0);
        long componentId = created.get("id").asLong();

        updateComponents(pump(sceneId, componentId,
                "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(names(getComponent(componentId), "events"))
                .as("обработчик, удалённый после снимка, обязан вернуться")
                .hasSize(2);
    }

    @Test
    void restoringVersionWithDeletedBinding_recreatesIt() throws Exception {
        long sceneId = newScene();
        String properties = "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],";
        String both = properties + "\"bindings\":["
                + "{\"component_property_name\":\"Уставка\",\"name\":\"цвет\",\"script\":\"{}\"},"
                + "{\"component_property_name\":\"Уставка\",\"name\":\"заливка\",\"script\":\"{}\"}]";
        JsonNode created = saveComponents(pump(sceneId, null, both)).get(0);
        long componentId = created.get("id").asLong();

        updateComponents(pump(sceneId, componentId, properties + "\"bindings\":["
                + "{\"component_property_name\":\"Уставка\",\"name\":\"цвет\",\"script\":\"{}\"}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(names(getComponent(componentId), "bindings"))
                .as("биндинг, удалённый после снимка, обязан вернуться")
                .hasSize(2);
    }

    /**
     * Обратная сторона правки: снимать id подряд у всех вложенных сущностей нельзя. Строка,
     * пережившая удаление соседки, обязана сохранить свой номер — иначе восстановление
     * обесценивает {@code scriptId} в открытых сессиях мониторинга ровно так же, как это делало
     * пересоздание строк при каждом сохранении.
     */
    @Test
    void restoringVersion_keepsIdsOfRowsThatStillExist() throws Exception {
        long sceneId = newScene();
        String both = "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"},"
                + "{\"name\":\"Закрыть\",\"script\":\"return 2;\"}]";
        JsonNode created = saveComponents(pump(sceneId, null, both)).get(0);
        long componentId = created.get("id").asLong();
        long survivingId = scriptId(created, "Открыть");

        updateComponents(pump(sceneId, componentId,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(scriptId(getComponent(componentId), "Открыть"))
                .as("id существующей строки восстановление менять не должно")
                .isEqualTo(survivingId);
    }

    /**
     * Предсуществующий баг, не внесённый веткой: {@code dropMissingIds} снимал id у удалённого
     * компонента, а {@code ComponentServiceImpl.updateComponent} звал {@code findById(null)} и
     * восстановление падало в 500 (scada-yxk).
     */
    @Test
    void restoringVersionWithDeletedComponent_recreatesIt() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pump(sceneId, null,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}]")).get(0);
        long componentId = created.get("id").asLong();

        mockMvc.perform(delete("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + componentId + "]"))
                .andExpect(status().isOk());

        restore(sceneId, 1);

        JsonNode scene = getComponent(sceneId);
        assertThat(scene.get("children"))
                .as("компонент, удалённый после снимка, обязан вернуться (с новым id)")
                .hasSize(1);
        assertThat(scene.get("children").get(0).get("name").asText()).isEqualTo("Насос");
        assertThat(scene.get("children").get(0).get("scripts"))
                .as("вложенные сущности воскресшего компонента тоже адресуются мёртвыми id")
                .hasSize(1);
    }
}
