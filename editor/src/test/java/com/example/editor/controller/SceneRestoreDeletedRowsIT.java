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

        updateScene(sceneId, pump(sceneId, componentId,
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

        updateScene(sceneId, pump(sceneId, componentId,
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

        updateScene(sceneId, pump(sceneId, componentId,
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

        updateScene(sceneId, pump(sceneId, componentId, properties + "\"bindings\":["
                + "{\"component_property_name\":\"Уставка\",\"name\":\"цвет\",\"script\":\"{}\"}]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        assertThat(names(getComponent(componentId), "bindings"))
                .as("биндинг, удалённый после снимка, обязан вернуться")
                .hasSize(2);
    }

    /**
     * Биндинг ссылается на свойство номером, и этот номер тоже может протухнуть.
     * <p>
     * {@code dropMissingIds} снимает id самих сущностей, но {@code component_property_id} внутри
     * биндинга — это ссылка, а не id строки, и её он не трогает. Свойство, удалённое после
     * снимка, восстановление создаёт заново <b>с новым id</b> (собственный мёртвый id свойства
     * снимается тем же {@code dropUnknownIds}, что и у остальных вложенных строк), а биндинг из
     * снимка продолжает адресовать старый — и {@code resolveBindingProperty} валит всё
     * восстановление в 400.
     * <p>
     * Сценарий «удалил и вернул как было» — обычная работа инженера, поэтому снимок обязан
     * нести имя свойства, а не только его номер.
     */
    @Test
    void restoringVersionWithDeletedBindingProperty_recreatesBoth() throws Exception {
        long sceneId = newScene();
        String properties = "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],";
        JsonNode created = saveComponents(pump(sceneId, null, properties + "\"bindings\":["
                + "{\"component_property_name\":\"Уставка\",\"name\":\"цвет\",\"script\":\"{}\"}]"))
                .get(0);
        long componentId = created.get("id").asLong();

        // Свойство удалено после снимка — вместе с ним уходит и биндинг на него.
        updateScene(sceneId, pump(sceneId, componentId, "\"properties\":[],\"bindings\":[]"),
                currentVersion(sceneId, "scenes"));

        restore(sceneId, 1);

        JsonNode component = getComponent(componentId);
        assertThat(names(component, "bindings"))
                .as("биндинг обязан вернуться вместе со свойством, а не уронить восстановление")
                .hasSize(1);
        assertThat(component.get("bindings").get(0).get("component_property_id").asLong())
                .as("ссылка обязана вести на воскресшее свойство, а не на его прежний номер")
                .isEqualTo(propertyId(component, "Уставка"));
    }

    /**
     * Обратная сторона правки: снимать id подряд у всех вложенных сущностей нельзя. Сценарий
     * подобран так, чтобы отличить принятую правку от отвергнутого варианта («снимать вложенные
     * id безусловно»): скрипт между версиями переименован, поэтому по имени пережившую строку
     * не найти — совпадение имён её не выдаёт. Единственный способ вернуть версии 1 её прежнее
     * имя, не потеряв id, — сопоставление по id, дошедшему из снимка живым. Если бы id снимался
     * безусловно, строка «Открыть» из версии 1 не нашлась бы среди текущих (там она называется
     * «Старт»), и восстановление создало бы новую строку с новым id вместо переименования старой.
     */
    @Test
    void restoringVersion_keepsIdsOfRowsThatStillExist() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pump(sceneId, null,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}]")).get(0);
        long componentId = created.get("id").asLong();
        long survivingId = scriptId(created, "Открыть");

        updateScene(sceneId, pump(sceneId, componentId,
                "\"scripts\":[{\"id\":" + survivingId + ",\"name\":\"Старт\",\"script\":\"return 1;\"}]"),
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
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(delete("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + componentId + "],\"based_on_version\":" + base + "}"))
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
