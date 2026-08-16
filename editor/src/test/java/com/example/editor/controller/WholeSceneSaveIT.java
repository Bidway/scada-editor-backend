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

    /**
     * C-1 (критично, найдено финальным ревью ветки): пустой {@code PUT} стирал сцену, не
     * записывая ни версии, ни строки в {@code command_log}. Снимок делался по сценам, вычисленным
     * из <b>записанного</b> ({@code snapshotScenesOf(prepared)}), а после пустого тела записывать
     * нечего — множество сцен выходило пустым, {@code record} не звался вовсе. Клиент получал 200
     * с {@code version_no: null}, сцена оказывалась пустой, а последняя версия в истории всё ещё
     * описывала полную сцену: восстановиться некуда и следа не осталось. Заодно это ломало
     * инвариант, на котором стоит слияние (живое состояние и последняя версия не расходятся,
     * потому что пишутся одной транзакцией) — {@code SceneMergeService.merge} берёт «чужое» из
     * живого состояния именно поэтому.
     * <p>
     * Проверка версии здесь — половина теста: без неё дефект и пережил весь прогон.
     */
    @Test
    void emptyPutWithSceneId_clearsTheScene() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");
        int versionsBefore = versionsOf(sceneId, "scenes").size();

        JsonNode response = updateSceneResponse(sceneId, "[]", base);

        assertThat(getComponent(sceneId).get("children")).isEmpty();
        assertThat(response.hasNonNull("version_no"))
                .as("стирание сцены обязано быть версией, а не 200 с version_no: null")
                .isTrue();
        assertThat(versionsOf(sceneId, "scenes").size())
                .as("в истории обязана появиться версия опустевшей сцены")
                .isEqualTo(versionsBefore + 1);
        assertThat(versionContent(sceneId, "scenes", base + 1).get("children"))
                .as("последняя версия обязана описывать пустую сцену, а не прежнюю полную")
                .isEmpty();
    }

    /**
     * C1 (критично, найдено ревью 5285aa4): «сначала пишем, потом чистим» удаляло компонент без
     * id той же транзакцией, где он был создан. IDENTITY выдаёт id на вставке, но в {@code keep}
     * чистки попадают только явные id из присланных dto — свежий id в него не входит, и чистка
     * после записи считала только что созданную строку чужой и удаляла её через orphanRemoval,
     * а клиенту при этом возвращался 200 с id уже несуществующей записи. Порядок исправлен на
     * «сначала чистка присланного, потом запись» — как в {@code SceneDocumentSource.restore}.
     */
    @Test
    void newComponentWithoutId_survivesAlongsideExisting() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(twoComponents(sceneId));
        long pumpId = created.get(0).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateScene(sceneId,
                "[{\"id\":" + pumpId + ",\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":"
                        + sceneId + "},"
                        + "{\"name\":\"Новый\",\"type\":\"valve\",\"parent_id\":" + sceneId + "}]",
                base);

        long newId = -1L;
        for (JsonNode c : updated) {
            if (c.get("id").asLong() != pumpId) {
                newId = c.get("id").asLong();
            }
        }
        assertThat(newId).as("PUT обязан вернуть id нового компонента").isNotEqualTo(-1L);

        assertThat(getComponent(sceneId).get("children"))
                .as("новый компонент без id не должен погибнуть в той же транзакции, где создан")
                .hasSize(2);
        assertThat(getComponent(newId).get("id").asLong())
                .as("id, вернувшийся клиенту в ответе, обязан реально существовать в базе")
                .isEqualTo(newId);
    }

    /**
     * I2 (найдено ревью 5285aa4): {@code scene_id} обязан адресовать именно сцену. Раньше он
     * подавался в чистку без проверки типа — id проекта прошёл бы точно так же, и по пустому
     * {@code components} чистка сочла бы «не присланными» все сцены под этим проектом и стёрла
     * бы их. Тип проверяется {@code requireScene} до любой записи.
     */
    @Test
    void sceneIdThatIsNotAScene_isRejectedWithoutTouchingChildren() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneId = createScene("scene-" + System.nanoTime(), projectId);
        saveComponents(twoComponents(sceneId));

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"scene_id\":" + projectId
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(getComponent(sceneId).get("children"))
                .as("отклонённый PUT с чужим scene_id не должен был тронуть сцену под ним")
                .hasSize(2);
    }

    /**
     * I-1 (найдено финальным ревью ветки): тело, названное одной сценой, но несущее компонент
     * другой. До ветки гард версии выводил набор сцен из тела и накрывал каждую задетую; с
     * планом 3b гардится только {@code scene_id}, а {@code updateComponent} берёт родителя из
     * {@code parent_id} каждого dto, ничьей принадлежности не проверяя. Итог был тройной:
     * чужая сцена правилась без проверки версии и без слияния, названная сцена вычищалась
     * целиком (её детей в теле нет), а версия записывалась чужой сцене с {@code
     * based_on_version} от названной. Принадлежность проверяется тем же обходом вверх
     * ({@code sceneRootIdOf}), которым гард версии искал сцены раньше.
     */
    @Test
    void componentOfAnotherScene_isRejectedAndTouchesNeitherScene() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneA = createScene("A-" + System.nanoTime(), projectId);
        long sceneB = createScene("B-" + System.nanoTime(), projectId);
        saveComponents(twoComponents(sceneA));
        long alienId = saveComponents("[{\"name\":\"Чужой\",\"type\":\"valve\",\"parent_id\":"
                + sceneB + "}]").get(0).get("id").asLong();
        Integer baseA = currentVersion(sceneA, "scenes");
        Integer baseB = currentVersion(sceneB, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"id\":" + alienId
                                + ",\"name\":\"Переименован\",\"type\":\"valve\",\"parent_id\":"
                                + sceneB + "}],\"scene_id\":" + sceneA
                                + ",\"based_on_version\":" + baseA + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(getComponent(sceneA).get("children"))
                .as("названную сцену нельзя вычищать по телу, которое ей не принадлежит")
                .hasSize(2);
        assertThat(getComponent(alienId).get("name").asText())
                .as("чужая сцена правится только своим PUT — со своим гардом версии")
                .isEqualTo("Чужой");
        assertThat(currentVersion(sceneB, "scenes"))
                .as("чужой сцене не место в истории с чужим же based_on_version")
                .isEqualTo(baseB);
    }

    /**
     * Тот же I-1, но уровнем ниже (scada-01z, найдено перепроверкой финального ревью): чужой
     * компонент едет не верхним элементом {@code components}, а внутри {@code children}
     * элемента, который сам принадлежит названной сцене. Гард раньше обходил только верхний
     * уровень списка, тогда как {@code populateComponent} рекурсивен и перепривязывает вложенный
     * dto к охватывающему компоненту, не глядя на его собственный {@code parent_id} — вложенный
     * узел угонялся из сцены B в сцену A тем же тройным вредом: без проверки версии B, без
     * снимка B (он ищется по верхнему уровню тела) и с 200 клиенту.
     */
    @Test
    void nestedComponentOfAnotherScene_isRejectedAndTouchesNeitherScene() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneA = createScene("A-" + System.nanoTime(), projectId);
        long sceneB = createScene("B-" + System.nanoTime(), projectId);
        long pumpId = saveComponents(twoComponents(sceneA)).get(0).get("id").asLong();
        long alienId = saveComponents("[{\"name\":\"Чужой\",\"type\":\"valve\",\"parent_id\":"
                + sceneB + "}]").get(0).get("id").asLong();
        Integer baseA = currentVersion(sceneA, "scenes");
        Integer baseB = currentVersion(sceneB, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"id\":" + pumpId
                                + ",\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneA
                                + ",\"children\":[{\"id\":" + alienId
                                + ",\"name\":\"Переименован\",\"type\":\"valve\",\"parent_id\":"
                                + pumpId + "}]}],\"scene_id\":" + sceneA
                                + ",\"based_on_version\":" + baseA + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(getComponent(sceneA).get("children"))
                .as("названную сцену нельзя вычищать по телу, которое ей не принадлежит")
                .hasSize(2);
        assertThat(getComponent(alienId).get("name").asText())
                .as("чужая сцена правится только своим PUT — со своим гардом версии")
                .isEqualTo("Чужой");
        assertThat(currentVersion(sceneB, "scenes"))
                .as("чужой сцене не место в истории с чужим же based_on_version")
                .isEqualTo(baseB);
    }

    /**
     * Обратная сторона того же гарда: {@code parent_id}, уводящий новый компонент в другую
     * сцену. Тут перезаписывать нечего, но компонент молча уехал бы в сцену, версия которой не
     * проверялась и снимок которой сделан от чужого {@code based_on_version}.
     */
    @Test
    void newComponentParentedIntoAnotherScene_isRejected() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        long sceneA = createScene("A-" + System.nanoTime(), projectId);
        long sceneB = createScene("B-" + System.nanoTime(), projectId);
        saveComponents(twoComponents(sceneA));
        Integer baseA = currentVersion(sceneA, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"name\":\"Подкидыш\",\"type\":\"valve\","
                                + "\"parent_id\":" + sceneB + "}],\"scene_id\":" + sceneA
                                + ",\"based_on_version\":" + baseA + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());

        assertThat(getComponent(sceneB).get("children"))
                .as("подкидыш не должен был доехать до чужой сцены")
                .isEmpty();
    }

    /**
     * I3 (найдено ревью 5285aa4): самый разрушительный запрос — пустой {@code components} — был
     * единственным, что шёл без проверки версии, потому что {@code requireBaseUnlessRestoring}
     * искал сцены по присланным dto, а по пустому списку не находил ни одной. Проверка версии
     * теперь ключуется по {@code scene_id}, а не по содержимому тела.
     */
    @Test
    void emptyComponentsWithStaleBase_isRejectedAsVersionConflict() throws Exception {
        long sceneId = newScene();
        saveComponents(twoComponents(sceneId));
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[],\"scene_id\":" + sceneId
                                + ",\"based_on_version\":" + (base + 100)
                                + ",\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isConflict());

        assertThat(getComponent(sceneId).get("children"))
                .as("отклонённый по версии PUT не должен был стереть детей сцены")
                .hasSize(2);
    }

    /**
     * Отклонение от буквального текста задания: конкурентное {@code updateScene} с тем же
     * {@code twoComponents(sceneId)} (без id) не годится для этого сценария — оно пересобирает
     * оба компонента заново (orphanRemoval + IDENTITY даёт новые id), и {@code pumpId} к моменту
     * DELETE уже не существует ни в какой сцене. {@code DeleteComponentCommand} использует
     * {@code repository::deleteById}, а он с Spring Data JPA 2.5+ на отсутствующий id не падает,
     * а молча ничего не делает — запрос по мёртвому id всегда вернёт 200 независимо от версии,
     * проверять тут нечего. Подтверждено прогоном: id ушли 3,4 → 5,6 (see task-8 report).
     * Конкурентное сохранение здесь вместо этого переименовывает насос, сохраняя его id — так
     * версия сцены бежит вперёд, а компонент, который тест пытается удалить, остаётся адресуемым.
     */
    @Test
    void deleteWithStaleVersion_isRejected() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(twoComponents(sceneId));
        long pumpId = created.get(0).get("id").asLong();
        long valveId = created.get(1).get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        // Кто-то сохранил сцену после того, как я её открыл.
        updateScene(sceneId, "[{\"id\":" + pumpId + ",\"name\":\"Насос-2\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + "},{\"id\":" + valveId + ",\"name\":\"Клапан\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + "}]", base);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + pumpId + "],\"based_on_version\":" + base + "}"))
                .andExpect(status().isConflict());

        assertThat(getComponent(sceneId).get("children"))
                .as("удаление по устаревшей версии не должно применяться")
                .hasSize(2);
    }
}
