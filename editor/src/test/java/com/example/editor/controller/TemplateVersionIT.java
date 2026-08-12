package com.example.editor.controller;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Снимки шаблонов. Механизм тот же, что у сцен, но дерево другое: шаблоны построены на
 * отдельной модели со своим маппером и своим путём сохранения.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateVersionIT extends EditorApiTestSupport {

    @Autowired
    private DocumentVersionRepository versionRepository;

    private static String tree(String defaultValue) {
        return "{\"name\":\"Клапан\",\"type\":\"faceplate\",\"rootComponent\":{"
                + "\"name\":\"Корень\",\"type\":\"group\","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"" + defaultValue + "\"}]}}";
    }

    private long createTemplate(String json) throws Exception {
        String body = mockMvc.perform(post("/api/editor/templates")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private List<DocumentVersion> versionsOf(long templateId) {
        return versionRepository.findByTargetTypeAndTargetIdOrderByVersionNoDesc(
                DocumentType.TEMPLATE, templateId);
    }

    @Test
    void creatingTemplate_createsFirstVersion() throws Exception {
        long templateId = createTemplate(tree("10"));

        assertThat(versionsOf(templateId)).hasSize(1);
        DocumentVersion version = versionsOf(templateId).get(0);
        assertThat(version.getKind()).isEqualTo(VersionKind.MANUAL);
        JsonNode content = version.getContent();
        assertThat(content.get("rootComponent").get("properties").get(0).get("id").asLong())
                .as("снимок берётся из ответа — с проставленными id")
                .isPositive();
    }

    @Test
    void updatingTemplate_createsSecondVersion() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tree("42")))
                .andExpect(status().isOk());

        assertThat(versionsOf(templateId)).hasSize(2);
        assertThat(versionsOf(templateId).get(0).getVersionNo()).isEqualTo(2);
    }

    @Test
    void updatingTemplateWithoutChanges_createsNoVersion() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tree("10")))
                .andExpect(status().isOk());

        assertThat(versionsOf(templateId)).hasSize(1);
    }
}
