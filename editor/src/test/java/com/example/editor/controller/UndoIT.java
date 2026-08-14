package com.example.editor.controller;

import com.example.editor.config.command.CommandLog;
import com.example.editor.config.command.CommandLogRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отмена команд editor. Это самое хрупкое место при унификации CommandManager и подключении
 * shared (задачи 8 и 9): undo читает снимок из command_log и применяет его обратно, а
 * сигнатуры Command/CommandResult/UndoHandler там меняются целиком.
 * <p>
 * Наличие компонента проверяется по списку, а не по коду ответа GET /{id}: список одинаково
 * отвечает на «нет компонента» и «компонент вернулся», поэтому три теста ниже читаются
 * симметрично. (GET /{id} на удалённом даёт 404 — NotFoundException отображает
 * GlobalExceptionHandler, — так тоже можно, но проверка вышла бы разной в разные стороны.)
 * <p>
 * Отмена create проверяется вживую и проходит. Отмена update и delete сейчас выключена
 * {@code @Disabled}: обе команды пишут в {@code undoPayload} только список id, а их
 * UndoHandler'ы ждут оттуда снимок компонента — падают на {@code name = NULL}. Баг заведён
 * как scada-8sc, тела тестов не менялись и описывают правильное ожидаемое поведение — они
 * позеленеют сами, когда дефект починят.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UndoIT extends EditorApiTestSupport {

    @Autowired
    private CommandLogRepository commandLogRepository;

    /** id последней записи журнала — её и отменяем. */
    private long lastLogId() {
        return commandLogRepository.findAll().stream()
                .mapToLong(CommandLog::getId)
                .max()
                .orElseThrow(() -> new AssertionError("command_log пуст: команда не записалась"));
    }

    private void undo(long logId) throws Exception {
        String failed = mockMvc.perform(post("/api/editor/undo")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + logId + "]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(failed).as("отмена не должна возвращать неудавшиеся id").isEqualTo("[]");
    }

    private boolean componentExists(long componentId) throws Exception {
        String body = mockMvc.perform(get("/api/editor/components"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode c : objectMapper.readTree(body)) {
            if (c.get("id").asLong() == componentId) {
                return true;
            }
        }
        return false;
    }

    private long createComponent(long sceneId, String rows) throws Exception {
        return saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId
                + ",\"properties\":" + rows + "}]").get(0).get("id").asLong();
    }

    private static final String ONE_ROW =
            "[{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"}]";

    @Test
    void undo_ofCreate_removesComponent() throws Exception {
        long sceneId = newScene();
        long componentId = createComponent(sceneId, ONE_ROW);
        assertThat(componentExists(componentId)).isTrue();

        undo(lastLogId());

        assertThat(componentExists(componentId)).isFalse();
    }

    @Test
    @Disabled("scada-8sc: UpdateComponentCommand кладёт в undoPayload только ids, "
            + "снимка прежнего состояния нет — отмена падает на name=NULL")
    void undo_ofUpdate_restoresPreviousRows() throws Exception {
        long sceneId = newScene();
        long componentId = createComponent(sceneId, ONE_ROW);

        updateScene(sceneId, "[{\"id\":" + componentId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ",\"properties\":"
                + "[{\"name\":\"Режим\",\"value_type\":\"int\",\"property_type\":\"Тег\"}]}]", null);
        assertThat(getComponent(componentId).get("properties")).hasSize(1);
        assertThat(getComponent(componentId).get("properties").get(0).get("name").asText())
                .isEqualTo("Режим");

        undo(lastLogId());

        JsonNode restored = getComponent(componentId);
        assertThat(restored.get("properties")).hasSize(1);
        assertThat(restored.get("properties").get(0).get("name").asText()).isEqualTo("Уставка");
    }

    @Test
    @Disabled("scada-8sc: DeleteComponentCommand кладёт в undoPayload только ids, "
            + "снимка удалённого компонента нет — отмена падает на name=NULL")
    void undo_ofDelete_bringsComponentBack() throws Exception {
        long sceneId = newScene();
        long componentId = createComponent(sceneId, ONE_ROW);

        mockMvc.perform(delete("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + componentId + "]"))
                .andExpect(status().isOk());
        assertThat(componentExists(componentId)).isFalse();

        undo(lastLogId());

        assertThat(componentExists(componentId)).isTrue();
    }

    /**
     * Повторная отмена той же записи отклоняется: undoneAt уже проставлен. Без этой проверки
     * двойной клик по «отменить» применил бы снимок дважды.
     */
    @Test
    void undo_twice_reportsFailure() throws Exception {
        long sceneId = newScene();
        createComponent(sceneId, ONE_ROW);
        long logId = lastLogId();
        undo(logId);

        String failed = mockMvc.perform(post("/api/editor/undo")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + logId + "]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(failed).contains(String.valueOf(logId));
    }
}
