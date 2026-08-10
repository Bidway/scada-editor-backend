package com.example.editor.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Хелперы поверх MockMvc. Компонент нельзя создать в корне: ComponentHierarchyValidator
 * пропускает без родителя только project, под project — только scene, и лишь под сценой
 * живут обычные компоненты. Поэтому каждый тест начинается с пары проект + сцена.
 * <p>
 * X-Username проставляет gateway, downstream его только читает — в тестах подставляем сами.
 */
public abstract class EditorApiTestSupport extends PostgresTestContainerSupport {

    protected static final String USER = "tester";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected long createProject(String name) throws Exception {
        String body = mockMvc.perform(post("/api/editor/components/project")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new NameOnly(name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    protected long createScene(String name, long projectId) throws Exception {
        String payload = "{\"name\":\"" + name + "\",\"project_id\":" + projectId + "}";
        String body = mockMvc.perform(post("/api/editor/components/scene")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Готовая сцена: возвращает её id, создав проект под ним. */
    protected long newScene() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        return createScene("scene-" + System.nanoTime(), projectId);
    }

    protected JsonNode saveComponents(String json) throws Exception {
        String body = mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected JsonNode updateComponents(String json) throws Exception {
        String body = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected JsonNode getComponent(long id) throws Exception {
        String body = mockMvc.perform(get("/api/editor/components/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** id свойства по имени — тесты сверяют именно их стабильность. */
    protected long propertyId(JsonNode component, String name) {
        for (JsonNode p : component.get("properties")) {
            if (name.equals(p.get("name").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("Нет свойства с именем '" + name + "' в " + component);
    }

    protected long scriptId(JsonNode component, String name) {
        for (JsonNode s : component.get("scripts")) {
            if (name.equals(s.get("name").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError("Нет скрипта с именем '" + name + "' в " + component);
    }

    private record NameOnly(String name) {
    }
}
