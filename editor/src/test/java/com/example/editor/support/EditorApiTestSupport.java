package com.example.editor.support;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        String body = mockMvc.perform(post("/api/editor/components/scene")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SceneRequest(name, projectId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Готовая сцена: возвращает её id, создав проект под ним. */
    protected long newScene() throws Exception {
        long projectId = createProject("proj-" + System.nanoTime());
        return createScene("scene-" + System.nanoTime(), projectId);
    }

    /** Сохранение без указания базовой версии — годится, пока у сцены версий нет. */
    protected JsonNode saveComponents(String componentsJson) throws Exception {
        return saveComponents(componentsJson, null, "MANUAL");
    }

    protected JsonNode saveComponents(String componentsJson, Integer basedOnVersion,
                                      String saveKind) throws Exception {
        String body = mockMvc.perform(post("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(componentsJson, basedOnVersion, saveKind)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("components");
    }

    /** Сохранение сцены целиком: с 3b это единственный корректный способ звать PUT. */
    protected JsonNode updateScene(long sceneId, String componentsJson, Integer basedOnVersion)
            throws Exception {
        return updateSceneResponse(sceneId, componentsJson, basedOnVersion).get("components");
    }

    /**
     * То же сохранение, но ответ целиком: кроме {@code components} в конверте едут
     * {@code version_no} и блок {@code merged}, и проверять их приходится тоже.
     */
    protected JsonNode updateSceneResponse(long sceneId, String componentsJson,
                                           Integer basedOnVersion) throws Exception {
        StringBuilder body = new StringBuilder("{\"components\":").append(componentsJson)
                .append(",\"scene_id\":").append(sceneId);
        if (basedOnVersion != null) {
            body.append(",\"based_on_version\":").append(basedOnVersion);
        }
        body.append(",\"save_kind\":\"MANUAL\"}");

        String response = mockMvc.perform(put("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** Вся история документа, новые версии первыми — в том порядке, в каком её отдаёт API. */
    protected JsonNode versionsOf(long documentId, String type) throws Exception {
        String body = mockMvc.perform(get("/api/editor/" + type + "/" + documentId + "/versions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Содержимое конкретной версии документа — снимок, а не живое состояние. */
    protected JsonNode versionContent(long documentId, String type, int versionNo) throws Exception {
        String body = mockMvc.perform(
                        get("/api/editor/" + type + "/" + documentId + "/versions/" + versionNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Текущий номер версии сцены; {@code null}, если версий ещё нет. */
    protected Integer currentVersion(long documentId, String type) throws Exception {
        JsonNode versions = versionsOf(documentId, type);
        return versions.isEmpty() ? null : versions.get(0).get("version_no").asInt();
    }

    private String envelope(String componentsJson, Integer basedOnVersion, String saveKind) {
        StringBuilder sb = new StringBuilder("{\"components\":").append(componentsJson);
        if (basedOnVersion != null) {
            sb.append(",\"based_on_version\":").append(basedOnVersion);
        }
        return sb.append(",\"save_kind\":\"").append(saveKind).append("\"}").toString();
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

    private record SceneRequest(String name, @JsonProperty("project_id") long projectId) {
    }
}
