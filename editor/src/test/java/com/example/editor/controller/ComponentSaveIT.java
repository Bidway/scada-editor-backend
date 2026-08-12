package com.example.editor.controller;

import com.example.editor.model.component.ComponentState;
import com.example.editor.repository.component.ComponentStateRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пересохранение компонента не должно пересоздавать вложенные сущности. За этим стоят
 * конкретные отказы контура: Script.id — это scriptId в {"type":"ACTION","scriptId":N},
 * и сессия мониторинга берёт дерево проекта один раз при старте. Пока id пересоздавались,
 * сохранение сцены посреди смены обесценивало id в открытых сессиях: оператор жал кнопку,
 * ACTION уходил со старым номером, скрипт не находился, клапан не срабатывал — без ошибки
 * на экране.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComponentSaveIT extends EditorApiTestSupport {

    @Autowired
    private ComponentStateRepository componentStateRepository;

    private String componentJson(long sceneId, Long id, String rows, String scripts,
                                 String states, String events) {
        return "[{"
                + (id == null ? "" : "\"id\":" + id + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":" + rows + ","
                + "\"scripts\":" + scripts + ","
                + "\"states\":" + states + ","
                + "\"events\":" + events
                + "}]";
    }

    private static final String TWO_ROWS =
            "[{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"},"
            + "{\"name\":\"Режим\",\"value_type\":\"int\",\"property_type\":\"Тег\"}]";

    private static final String ONE_SCRIPT =
            "[{\"name\":\"Открыть клапан\",\"script\":\"writeTag('T1', 1)\"}]";

    private static final String ONE_STATE =
            "[{\"name\":\"Открыт\",\"image\":{\"fill\":\"green\"},\"isDefault\":true}]";

    private static final String ONE_EVENT =
            "[{\"event_type\":\"onClick\",\"script\":\"runScript('Открыть клапан')\"}]";

    /**
     * Легаси-данные: имя с краевым пробелом, сохранённое до того, как вход стали тримить.
     * Сопоставление строит карту по сырому имени из БД, а входящее нормализует, поэтому такая
     * строка не находилась и каждое сохранение выглядело как «удалили одну, добавили другую»
     * (scada-w51). Завести её через API нельзя — только положив руками, как она и легла.
     */
    @Test
    void resave_matchesStateWithLegacyPaddedName() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();
        long stateId = created.get("states").get(0).get("id").asLong();

        ComponentState legacy = componentStateRepository.findById(stateId).orElseThrow();
        legacy.setName("  Открыт  ");
        componentStateRepository.save(legacy);

        JsonNode resaved = updateComponents(
                componentJson(sceneId, componentId, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);

        assertThat(resaved.get("states")).hasSize(1);
        assertThat(resaved.get("states").get(0).get("id").asLong())
                .as("состояние должно найтись по имени без краевых пробелов, а не пересоздаться")
                .isEqualTo(stateId);
        assertThat(componentStateRepository.findById(stateId)).isPresent();
    }

    @Test
    void resave_keepsPropertyIds() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();
        long setpointId = propertyId(created, "Уставка");
        long modeId = propertyId(created, "Режим");

        JsonNode resaved = updateComponents(
                componentJson(sceneId, componentId, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);

        assertThat(propertyId(resaved, "Уставка")).isEqualTo(setpointId);
        assertThat(propertyId(resaved, "Режим")).isEqualTo(modeId);
    }

    @Test
    void resave_keepsScriptIds() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();
        long openId = scriptId(created, "Открыть клапан");

        JsonNode resaved = updateComponents(componentJson(sceneId, componentId, TWO_ROWS,
                "[{\"name\":\"Открыть клапан\",\"script\":\"writeTag('T1', 100)\"}]",
                ONE_STATE, ONE_EVENT)).get(0);

        assertThat(scriptId(resaved, "Открыть клапан")).isEqualTo(openId);
        assertThat(resaved.get("scripts").get(0).get("script").asText())
                .isEqualTo("writeTag('T1', 100)");
    }

    @Test
    void resave_keepsStateAndEventIds() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();
        long stateId = created.get("states").get(0).get("id").asLong();
        long eventId = created.get("events").get(0).get("id").asLong();

        JsonNode resaved = updateComponents(
                componentJson(sceneId, componentId, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);

        assertThat(resaved.get("states").get(0).get("id").asLong()).isEqualTo(stateId);
        assertThat(resaved.get("events").get(0).get("id").asLong()).isEqualTo(eventId);
    }

    /**
     * Прежний clear() + insert обработчика того же типа давал в одной транзакции DELETE и
     * INSERT с одним ключом (UNIQUE component_id, event_type), а Hibernate на flush делает
     * вставки раньше удалений — запрос падал. Ломалось самое частое действие: пересохранение
     * сцены, где у кнопки уже есть onClick.
     */
    @Test
    void resave_withSameEventType_doesNotViolateUniqueConstraint() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();

        JsonNode resaved = updateComponents(componentJson(sceneId, componentId, TWO_ROWS,
                ONE_SCRIPT, ONE_STATE,
                "[{\"event_type\":\"onClick\",\"script\":\"runScript('Другой')\"}]")).get(0);

        assertThat(resaved.get("events")).hasSize(1);
        assertThat(resaved.get("events").get(0).get("script").asText())
                .isEqualTo("runScript('Другой')");
    }

    @Test
    void resave_dropsRowsMissingFromPayload() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();
        long setpointId = propertyId(created, "Уставка");

        JsonNode resaved = updateComponents(componentJson(sceneId, componentId,
                "[{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"},"
                + "{\"name\":\"Новая\",\"value_type\":\"bool\",\"property_type\":\"Тег\"}]",
                ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);

        assertThat(resaved.get("properties")).hasSize(2);
        assertThat(propertyId(resaved, "Уставка")).isEqualTo(setpointId);
        assertThat(propertyId(resaved, "Новая")).isPositive();
    }

    /**
     * properties == null означает «не прислано» и существующие строки не трогает — их можно
     * вести и точечно через ComponentPropertyController. В отличие от scripts/states/events,
     * где отсутствие поля означает «их нет».
     */
    @Test
    void resave_withNullProperties_keepsExistingRows() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, TWO_ROWS, ONE_SCRIPT, ONE_STATE, ONE_EVENT)).get(0);
        long componentId = created.get("id").asLong();

        updateComponents("[{\"id\":" + componentId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ",\"scripts\":" + ONE_SCRIPT + "}]");

        assertThat(getComponent(componentId).get("properties")).hasSize(2);
    }
}
