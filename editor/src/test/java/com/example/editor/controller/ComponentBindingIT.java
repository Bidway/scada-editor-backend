package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Биндинг адресует свойство по id или по имени. Имя нужно для строки, создаваемой этим же
 * запросом: id у неё появится только после flush, а привязать биндинг надо сейчас. Раньше
 * такой запрос падал в 500 — свойство искалось глобальным findById и находилось уже
 * удалённым синхронизацией свойств (TransientObjectException из недр Hibernate).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComponentBindingIT extends EditorApiTestSupport {

    @Test
    void bindingByName_attachesToRowCreatedInSameRequest() throws Exception {
        long sceneId = newScene();

        JsonNode created = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"bindings\":[{\"component_property_name\":\"Уставка\","
                + "\"name\":\"цвет\",\"script\":\"value > 10 ? 'red' : 'green'\"}]}]").get(0);

        long setpointId = propertyId(created, "Уставка");
        assertThat(created.get("bindings")).hasSize(1);
        assertThat(created.get("bindings").get(0).get("component_property_id").asLong())
                .isEqualTo(setpointId);
    }

    @Test
    void bindingById_attachesToExistingRow() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}]}]").get(0);
        long componentId = created.get("id").asLong();
        long setpointId = propertyId(created, "Уставка");
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}],"
                + "\"bindings\":[{\"component_property_id\":" + setpointId + ","
                + "\"name\":\"цвет\",\"script\":\"'red'\"}]}]", base).get(0);

        assertThat(updated.get("bindings").get(0).get("component_property_id").asLong())
                .isEqualTo(setpointId);
    }

    @Test
    void removingBoundProperty_removesBindingWithoutForeignKeyViolation() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Скорость\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"},"
                + "{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"}],"
                + "\"bindings\":[{\"component_property_name\":\"Уставка\","
                + "\"name\":\"цвет\",\"script\":\"'red'\"}]}]").get(0);
        long componentId = created.get("id").asLong();
        assertThat(created.get("properties")).hasSize(2);
        assertThat(created.get("bindings")).hasSize(1);
        Integer base = currentVersion(sceneId, "scenes");

        JsonNode updated = updateComponents("[{\"id\":" + componentId + ",\"name\":\"Насос\","
                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Скорость\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}]}]", base).get(0);

        assertThat(updated.get("properties")).hasSize(1);
        assertThat(updated.get("bindings")).isEmpty();
    }

    @Test
    void bindingToUnknownName_isRejected() throws Exception {
        long sceneId = newScene();
        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"name\":\"Насос\",\"type\":\"valve\","
                                + "\"parent_id\":" + sceneId + ","
                                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                                + "\"property_type\":\"Тег\"}],"
                                + "\"bindings\":[{\"component_property_name\":\"Нет такой\","
                                + "\"name\":\"цвет\",\"script\":\"'red'\"}]}],"
                                + "\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bindingToPropertyOfAnotherComponent_isRejectedWithOwnershipMessage() throws Exception {
        long sceneId = newScene();
        JsonNode other = saveComponents("[{\"name\":\"Клапан\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}]}]").get(0);
        long foreignPropertyId = propertyId(other, "Уставка");
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"name\":\"Насос\",\"type\":\"valve\","
                                + "\"parent_id\":" + sceneId + ","
                                + "\"bindings\":[{\"component_property_id\":" + foreignPropertyId + ","
                                + "\"name\":\"цвет\",\"script\":\"'red'\"}]}],"
                                + "\"based_on_version\":" + base + ","
                                + "\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("does not belong to component")));
    }

    @Test
    void bindingToOwnPropertyDroppedFromRequest_isRejectedWithDistinctMessage() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Скорость\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"},"
                + "{\"name\":\"Уставка\",\"value_type\":\"double\",\"property_type\":\"Тег\"}]}]").get(0);
        long componentId = created.get("id").asLong();
        long setpointId = propertyId(created, "Уставка");
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"id\":" + componentId + ",\"name\":\"Насос\","
                                + "\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                                + "\"properties\":[{\"name\":\"Скорость\",\"value_type\":\"double\","
                                + "\"property_type\":\"Тег\"}],"
                                + "\"bindings\":[{\"component_property_id\":" + setpointId + ","
                                + "\"name\":\"цвет\",\"script\":\"'red'\"}]}],"
                                + "\"based_on_version\":" + base + ","
                                + "\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not among the properties sent")));
    }

    @Test
    void bindingWithoutAddress_isRejected() throws Exception {
        long sceneId = newScene();
        mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"name\":\"Насос\",\"type\":\"valve\","
                                + "\"parent_id\":" + sceneId + ","
                                + "\"bindings\":[{\"name\":\"цвет\",\"script\":\"'red'\"}]}],"
                                + "\"save_kind\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
    }
}
