package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Имя — адрес сущности в контуре: по нему значения наборов находят строку, writeTag
 * адресует свойство, runScript ищет скрипт, setState выбирает состояние. Одноимённые
 * сущности сделали бы все эти привязки неоднозначными, поэтому отвергаются на входе.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComponentValidationIT extends EditorApiTestSupport {

    private void expectRejected(String body) throws Exception {
        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicatePropertyNames_areRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":["
                + "{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"},"
                + "{\"name\":\"Уставка\",\"value_type\":\"int\",\"property_type\":\"Тег\"}]}]");
    }

    @Test
    void duplicateScriptNames_areRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"scripts\":[{\"name\":\"Пуск\",\"script\":\"a()\"},"
                + "{\"name\":\"Пуск\",\"script\":\"b()\"}]}]");
    }

    @Test
    void duplicateStateNames_areRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"states\":[{\"name\":\"Открыт\",\"image\":{},\"isDefault\":true},"
                + "{\"name\":\"Открыт\",\"image\":{},\"isDefault\":false}]}]");
    }

    @Test
    void blankStateName_isRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"states\":[{\"name\":\"  \",\"image\":{},\"isDefault\":true}]}]");
    }

    @Test
    void duplicateEventTypes_areRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"},"
                + "{\"event_type\":\"onClick\",\"script\":\"b()\"}]}]");
    }

    @Test
    void unknownEventType_isRejected() throws Exception {
        long sceneId = newScene();
        expectRejected("[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"events\":[{\"event_type\":\"onSwipe\",\"script\":\"a()\"}]}]");
    }
}
