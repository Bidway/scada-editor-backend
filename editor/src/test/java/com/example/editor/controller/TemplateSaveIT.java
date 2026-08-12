package com.example.editor.controller;

import com.example.editor.model.template.TemplateComponent;
import com.example.editor.repository.template.TemplateComponentRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сохранение шаблона не должно пересоздавать дерево. API шаблонов принимает только дерево
 * целиком — точечных эндпоинтов нет, — поэтому правка одного значения по умолчанию едет тем же
 * запросом, что и всё остальное. Пока каждое сохранение выдавало новые id, поточечная история
 * правок шаблона была невозможна: привязывать запись было не к чему (scada-eap).
 * <p>
 * Ключ сопоставления — имя: у DTO шаблона id нет вовсе (только клиентский {@code key}, в базе
 * он не хранится), а имя уже служит ключом у свойств, скриптов и состояний компонента.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateSaveIT extends EditorApiTestSupport {

    @Autowired
    private TemplateComponentRepository templateComponentRepository;

    private static String tree(String setpointDefault, String script) {
        return "{\"name\":\"Клапан\",\"type\":\"faceplate\",\"rootComponent\":{"
                + "\"name\":\"Корень\",\"type\":\"group\","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"" + setpointDefault + "\"}],"
                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"" + script + "\"}],"
                + "\"states\":[{\"name\":\"Открыт\",\"isDefault\":true}],"
                + "\"children\":[{\"name\":\"Индикатор\",\"type\":\"lamp\","
                + "\"properties\":[{\"name\":\"Цвет\",\"value_type\":\"string\","
                + "\"property_type\":\"Локальный\"}]}]}}";
    }

    private JsonNode createTemplate(String json) throws Exception {
        String body = mockMvc.perform(post("/api/editor/templates")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode updateTemplate(long id, String json) throws Exception {
        String body = mockMvc.perform(put("/api/editor/templates/" + id)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode getTemplate(long id) throws Exception {
        String body = mockMvc.perform(get("/api/editor/templates/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** id всего дерева одной строкой — так расхождение видно целиком, а не по первому падению. */
    private static String idsOf(JsonNode template) {
        JsonNode root = template.get("rootComponent");
        JsonNode child = root.get("children").get(0);
        return "root=" + root.get("id")
                + " property=" + root.get("properties").get(0).get("id")
                + " script=" + root.get("scripts").get(0).get("id")
                + " state=" + root.get("states").get(0).get("id")
                + " child=" + child.get("id")
                + " childProperty=" + child.get("properties").get(0).get("id");
    }

    @Test
    void resave_keepsIdsOfWholeTree() throws Exception {
        JsonNode created = createTemplate(tree("10", "open()"));
        long templateId = created.get("id").asLong();
        String before = idsOf(created);

        updateTemplate(templateId, tree("42", "open2()"));

        JsonNode after = getTemplate(templateId);
        assertThat(idsOf(after)).isEqualTo(before);
        assertThat(after.get("rootComponent").get("properties").get(0).get("default_value").asText())
                .as("правка должна доехать, а не потеряться вместе с пересозданием")
                .isEqualTo("42");
        assertThat(after.get("rootComponent").get("scripts").get(0).get("script").asText())
                .isEqualTo("open2()");
    }

    /**
     * Связь template → rootComponent объявлена без cascade и orphanRemoval, поэтому прежнее
     * дерево когда-то оставалось в базе целиком на каждое сохранение. По API этого не видно:
     * шаблон отдаёт только текущий корень.
     */
    @Test
    void resave_leavesNoOrphanComponents() throws Exception {
        JsonNode created = createTemplate(tree("10", "open()"));
        long templateId = created.get("id").asLong();

        updateTemplate(templateId, tree("42", "open2()"));

        assertThat(componentsOf(templateId))
                .as("корень и его единственный потомок, без копий прежнего дерева")
                .hasSize(2);
    }

    /**
     * Фронт называет фигуры типовыми именами: в рабочей базе уже лежит шаблон с двумя детьми
     * `Element` (circle и line). Одноимённых соседей поэтому нельзя ни отвергать, ни путать
     * между собой — их разводит номер среди одноимённых.
     */
    @Test
    void resave_keepsIdsOfSiblingsWithSameName() throws Exception {
        String twins = "{\"name\":\"Клапан\",\"type\":\"faceplate\",\"rootComponent\":{"
                + "\"name\":\"Корень\",\"type\":\"group\",\"children\":["
                + "{\"name\":\"Element\",\"type\":\"circle\"},"
                + "{\"name\":\"Element\",\"type\":\"line\"}]}}";

        JsonNode created = createTemplate(twins);
        long templateId = created.get("id").asLong();
        JsonNode createdChildren = created.get("rootComponent").get("children");
        long firstId = createdChildren.get(0).get("id").asLong();
        long secondId = createdChildren.get(1).get("id").asLong();

        updateTemplate(templateId, twins);

        JsonNode children = getTemplate(templateId).get("rootComponent").get("children");
        assertThat(children).hasSize(2);
        assertThat(children.get(0).get("id").asLong()).isEqualTo(firstId);
        assertThat(children.get(1).get("id").asLong()).isEqualTo(secondId);
        assertThat(children.get(0).get("type").asText()).isEqualTo("circle");
        assertThat(children.get(1).get("type").asText()).isEqualTo("line");
        assertThat(componentsOf(templateId)).hasSize(3);
    }

    /** Компонент, выпавший из присланного дерева, должен уйти из базы, а не остаться сиротой. */
    @Test
    void resave_withoutChild_removesIt() throws Exception {
        JsonNode created = createTemplate(tree("10", "open()"));
        long templateId = created.get("id").asLong();

        updateTemplate(templateId, "{\"name\":\"Клапан\",\"type\":\"faceplate\","
                + "\"rootComponent\":{\"name\":\"Корень\",\"type\":\"group\"}}");

        JsonNode after = getTemplate(templateId);
        assertThat(after.get("rootComponent").get("children")).isEmpty();
        assertThat(componentsOf(templateId)).hasSize(1);
    }

    private java.util.List<TemplateComponent> componentsOf(long templateId) {
        return templateComponentRepository.findAll().stream()
                .filter(c -> templateId == c.getTemplateId())
                .toList();
    }
}
