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

    /** Компонент с произвольным набором вложенных коллекций. */
    private String component(long sceneId, Long componentId, String nested) {
        return "[{" + (componentId == null ? "" : "\"id\":" + componentId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + nested + "}]";
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
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть клапан", originalScriptId, "Норма", null), base).get(0);

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
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть", null, "Нормальное", originalStateId), base).get(0);

        assertThat(updated.get("states")).hasSize(1);
        assertThat(stateId(updated, "Нормальное")).isEqualTo(originalStateId);
    }

    /**
     * Переименование по id и создание новой строки под освободившимся именем — одним запросом.
     * <p>
     * Итоговое состояние {@code UNIQUE (component_id, name)} не нарушает, но записать его в лоб
     * нельзя: Hibernate на flush выполняет вставки раньше обновлений, и INSERT новой строки
     * «Открыть» уходит в базу прежде, чем UPDATE уведёт старую строку с этого имени. Раньше это
     * отвергалось четырёхсотой с объяснением; теперь переименование проводится через временное
     * имя с промежуточным flush, и запрос проходит (scada-wob).
     */
    @Test
    void renameAndReuseOfTheFreedName_isApplied() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(component(sceneId, componentId,
                "\"scripts\":[{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                        + "\"script\":\"return 1;\"},"
                        + "{\"name\":\"Открыть\",\"script\":\"return 2;\"}],"
                        + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]"),
                base).get(0);

        assertThat(updated.get("scripts")).hasSize(2);
        assertThat(scriptId(updated, "Закрыть"))
                .as("прислали id — это переименование, строка обязана остаться собой")
                .isEqualTo(originalScriptId);
        assertThat(scriptId(updated, "Открыть"))
                .as("освободившееся имя занимает новая строка, а не воскресшая старая")
                .isNotEqualTo(originalScriptId);
    }

    /**
     * Обмен именами между двумя существующими строками. Сложнее предыдущего случая: здесь нет
     * ни одной новой строки, конфликтуют два UPDATE между собой, и порядок их выполнения внутри
     * flush не определён вовсе. Разводит их только временное имя.
     */
    @Test
    void swappingTwoScriptNames_isApplied() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(component(sceneId, null,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"},"
                        + "{\"name\":\"Закрыть\",\"script\":\"return 2;\"}]")).get(0);
        long componentId = created.get("id").asLong();
        long openId = scriptId(created, "Открыть");
        long closeId = scriptId(created, "Закрыть");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(component(sceneId, componentId,
                "\"scripts\":[{\"id\":" + openId + ",\"name\":\"Закрыть\",\"script\":\"return 1;\"},"
                        + "{\"id\":" + closeId + ",\"name\":\"Открыть\",\"script\":\"return 2;\"}]"),
                base).get(0);

        assertThat(updated.get("scripts")).hasSize(2);
        assertThat(scriptId(updated, "Закрыть")).isEqualTo(openId);
        assertThat(scriptId(updated, "Открыть")).isEqualTo(closeId);
    }

    /** Состояния живут в {@code ComponentServiceImpl.applyStates} — код тот же, констрейнт свой. */
    @Test
    void swappingTwoStateNames_isApplied() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(component(sceneId, null,
                "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true},"
                        + "{\"name\":\"Авария\",\"image\":{},\"isDefault\":false}]")).get(0);
        long componentId = created.get("id").asLong();
        long normalId = stateId(created, "Норма");
        long alarmId = stateId(created, "Авария");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(component(sceneId, componentId,
                "\"states\":[{\"id\":" + normalId + ",\"name\":\"Авария\",\"image\":{},"
                        + "\"isDefault\":true},"
                        + "{\"id\":" + alarmId + ",\"name\":\"Норма\",\"image\":{},"
                        + "\"isDefault\":false}]"), base).get(0);

        assertThat(updated.get("states")).hasSize(2);
        assertThat(stateId(updated, "Авария")).isEqualTo(normalId);
        assertThat(stateId(updated, "Норма")).isEqualTo(alarmId);
    }

    /** У событий ключ — {@code event_type}, констрейнт {@code component_event_uk}. */
    @Test
    void swappingTwoEventTypes_isApplied() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(component(sceneId, null,
                "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"},"
                        + "{\"event_type\":\"onHover\",\"script\":\"b()\"}]")).get(0);
        long componentId = created.get("id").asLong();
        long clickId = eventId(created, "onClick");
        long hoverId = eventId(created, "onHover");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(component(sceneId, componentId,
                "\"events\":[{\"id\":" + clickId + ",\"event_type\":\"onHover\",\"script\":\"a()\"},"
                        + "{\"id\":" + hoverId + ",\"event_type\":\"onClick\",\"script\":\"b()\"}]"),
                base).get(0);

        assertThat(updated.get("events")).hasSize(2);
        assertThat(eventId(updated, "onHover")).isEqualTo(clickId);
        assertThat(eventId(updated, "onClick")).isEqualTo(hoverId);
    }

    @Test
    void plainRenameById_stillWorks() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");
        Integer base = currentVersion(sceneId, "scenes");

        // Переименование без занятия освободившегося имени — обычный случай, он проходить обязан.
        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Закрыть", originalScriptId, "Норма", null), base).get(0);

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
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(componentJson(
                sceneId, componentId, "Открыть", null, "Норма", null), base).get(0);

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

        // Обратный порядок относительно renameAndReuseOfTheFreedName_isApplied: элемент без id
        // идёт первым. Без двухпроходного разрешения оба элемента находят одну и ту же строку по
        // id (карта имён чистится только когда элемент С id обработан, а он ещё не наступил) —
        // второй set молча затирает первый, и скрипт "Открыть" пропадает без ошибки. Порядок
        // элементов в массиве не должен значить ничего.
        Integer base = currentVersion(sceneId, "scenes");
        JsonNode updated = updateComponents(component(sceneId, componentId,
                "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"},"
                        + "{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                        + "\"script\":\"return 2;\"}],"
                        + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]"),
                base).get(0);

        assertThat(updated.get("scripts"))
                .as("явный id не должен молча съедать элемент без id того же имени")
                .hasSize(2);
        assertThat(scriptId(updated, "Закрыть")).isEqualTo(originalScriptId);
        assertThat(scriptId(updated, "Открыть")).isNotEqualTo(originalScriptId);
    }

    @Test
    void sameIdTwice_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalScriptId = scriptId(created, "Открыть");
        Integer base = currentVersion(sceneId, "scenes");

        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"id\":" + componentId + ","
                                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                                + "\"scripts\":[{\"id\":" + originalScriptId + ",\"name\":\"Открыть\","
                                + "\"script\":\"return 1;\"},"
                                + "{\"id\":" + originalScriptId + ",\"name\":\"Закрыть\","
                                + "\"script\":\"return 2;\"}],"
                                + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}]}],"
                                + "\"based_on_version\":" + base + ","
                                + "\"save_kind\":\"MANUAL\"}"))
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
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(
                withBinding(sceneId, componentId, "цвет", null), base).get(0);

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
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(
                withBinding(sceneId, componentId, "заливка", originalBindingId), base).get(0);

        assertThat(updated.get("bindings")).hasSize(1);
        assertThat(bindingId(updated, "заливка")).isEqualTo(originalBindingId);
    }

    private long eventId(JsonNode component, String eventType) {
        for (JsonNode e : component.get("events")) {
            if (eventType.equals(e.get("event_type").asText())) {
                return e.get("id").asLong();
            }
        }
        throw new AssertionError("Нет обработчика '" + eventType + "' в " + component);
    }

    /**
     * У обработчиков ключ сопоставления — тип, а не имя, и на нём висит UNIQUE
     * {@code (component_id, event_type)}. Смена типа по id — то же самое, что переименование у
     * соседей: строка обязана остаться той же, иначе пара DELETE+INSERT в одной транзакции
     * упирается в этот констрейнт (порядок операций внутри flush не поменять).
     */
    @Test
    void changingEventTypeById_keepsItsId() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(withEvent(sceneId, null, "onClick", null)).get(0);
        long componentId = created.get("id").asLong();
        long originalEventId = eventId(created, "onClick");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents(
                withEvent(sceneId, componentId, "onHover", originalEventId), base).get(0);

        assertThat(updated.get("events")).hasSize(1);
        assertThat(eventId(updated, "onHover"))
                .as("прислали id — это смена типа, а не новая строка")
                .isEqualTo(originalEventId);
    }

    private String withEvent(long sceneId, Long componentId, String eventType, Long eventId) {
        return "[{" + (componentId == null ? "" : "\"id\":" + componentId + ",")
                + "\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"events\":[{" + (eventId == null ? "" : "\"id\":" + eventId + ",")
                + "\"event_type\":\"" + eventType + "\",\"script\":\"a()\"}]}]";
    }

    /**
     * Полный компонент под именем {@code name}: по одной строке каждого вида, чтобы у второго
     * компонента было чем «одолжить» чужой id.
     */
    private String fullComponent(String name, long sceneId) {
        return "{\"name\":\"" + name + "\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}],"
                + "\"states\":[{\"name\":\"Норма\",\"image\":{},\"isDefault\":true}],"
                + "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"}],"
                + "\"bindings\":[{\"component_property_name\":\"Уставка\",\"name\":\"цвет\","
                + "\"script\":\"{}\"}]}";
    }

    private String rejectedUpdate(String componentsJson, Integer base) throws Exception {
        return mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":" + componentsJson
                                + ",\"based_on_version\":" + base
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Чужой вложенный id — это ошибка запроса, а не повод молча создать новую строку: клиент
     * адресовал конкретную строку и обязан узнать, что промахнулся. Отдельно проверяем, что
     * сообщение называет и id, и компонент — именно по нему в C1 нашлась причина 400 на
     * восстановлении.
     */
    @Test
    void scriptIdOfAnotherComponent_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode saved = saveComponents(
                "[" + fullComponent("Насос", sceneId) + "," + fullComponent("Клапан", sceneId) + "]");
        long componentId = saved.get(0).get("id").asLong();
        long foreignScriptId = scriptId(saved.get(1), "Открыть");
        Integer base = currentVersion(sceneId, "scenes");

        String body = rejectedUpdate("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{\"id\":" + foreignScriptId + ",\"name\":\"Открыть\","
                + "\"script\":\"return 1;\"}]}]", base);

        assertThat(body).contains("Script " + foreignScriptId + " does not belong to component "
                + componentId);
    }

    @Test
    void stateIdOfAnotherComponent_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode saved = saveComponents(
                "[" + fullComponent("Насос", sceneId) + "," + fullComponent("Клапан", sceneId) + "]");
        long componentId = saved.get(0).get("id").asLong();
        long foreignStateId = stateId(saved.get(1), "Норма");
        Integer base = currentVersion(sceneId, "scenes");

        String body = rejectedUpdate("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"states\":[{\"id\":" + foreignStateId + ",\"name\":\"Норма\",\"image\":{},"
                + "\"isDefault\":true}]}]", base);

        assertThat(body).contains("State " + foreignStateId + " does not belong to component "
                + componentId);
    }

    @Test
    void eventIdOfAnotherComponent_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode saved = saveComponents(
                "[" + fullComponent("Насос", sceneId) + "," + fullComponent("Клапан", sceneId) + "]");
        long componentId = saved.get(0).get("id").asLong();
        long foreignEventId = eventId(saved.get(1), "onClick");
        Integer base = currentVersion(sceneId, "scenes");

        String body = rejectedUpdate("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"events\":[{\"id\":" + foreignEventId + ",\"event_type\":\"onClick\","
                + "\"script\":\"a()\"}]}]", base);

        assertThat(body).contains("Event " + foreignEventId + " does not belong to component "
                + componentId);
    }

    @Test
    void bindingIdOfAnotherComponent_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode saved = saveComponents(
                "[" + fullComponent("Насос", sceneId) + "," + fullComponent("Клапан", sceneId) + "]");
        long componentId = saved.get(0).get("id").asLong();
        long foreignBindingId = bindingId(saved.get(1), "цвет");
        Integer base = currentVersion(sceneId, "scenes");

        String body = rejectedUpdate("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"bindings\":[{\"id\":" + foreignBindingId + ","
                + "\"component_property_name\":\"Уставка\",\"name\":\"цвет\","
                + "\"script\":\"{}\"}]}]", base);

        assertThat(body).contains("Binding " + foreignBindingId + " does not belong to component "
                + componentId);
    }

    /** Несуществующий id неотличим от чужого: строки нет среди строк компонента — отказ. */
    @Test
    void unknownNestedId_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(
                componentJson(sceneId, null, "Открыть", null, "Норма", null)).get(0);
        long componentId = created.get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        String body = rejectedUpdate(componentJson(
                sceneId, componentId, "Открыть", 999_999_999L, "Норма", null), base);

        assertThat(body).contains("Script 999999999 does not belong to component " + componentId);
    }
}
