package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * scada-8fw: битый скрипт раньше ложился в базу молча и падал только при первом исполнении —
 * на объекте, при нажатии кнопки оператором.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScriptValidationIT extends EditorApiTestSupport {

    private String component(long sceneId, String scripts, String bindings) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"ST\",\"value_type\":\"int\",\"property_type\":\"Тег\"}],"
                + "\"scripts\":" + scripts + ",\"bindings\":" + bindings + "}]";
    }

    @Test
    void brokenActionScript_isRejectedAndNothingSaved() throws Exception {
        long sceneId = newScene();
        String broken = component(sceneId,
                "[{\"name\":\"Открыть\",\"script\":\"writeTag('T1', 1\"}]", "[]");

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(broken, null, "MANUAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("script 'Открыть'")));

        assertThat(getComponent(sceneId).get("children")).isEmpty();
    }

    /**
     * Скрипты исполняются как тело функции, поэтому {@code return} на верхнем уровне — не ошибка.
     * У биндинга JavaScript лежит в поле {@code code} JSON-конфига: проверяется именно он.
     */
    @Test
    void functionBodyScripts_areAccepted_andBrokenBindingCodeIsRejected() throws Exception {
        long sceneId = newScene();
        String validBinding = "[{\"name\":\"ST\",\"component_property_name\":\"ST\","
                + "\"script\":\"{\\\"v\\\":1,\\\"code\\\":\\\"if (!ST) return; setState('On')\\\"}\"}]";
        saveComponents(component(sceneId,
                "[{\"name\":\"Открыть\",\"script\":\"if (x) return; writeTag('T1', 1)\"}]", validBinding));

        String brokenBinding = "[{\"name\":\"ST\",\"component_property_name\":\"ST\","
                + "\"script\":\"{\\\"v\\\":1,\\\"code\\\":\\\"setState(\\\"}\"}]";
        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(component(newScene(), "[]", brokenBinding), null, "MANUAL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("binding 'ST'")));
    }
}
