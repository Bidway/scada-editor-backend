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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private static String treeWithBase(String defaultValue, int baseVersion) {
        return tree(defaultValue).replaceFirst("^\\{",
                "{\"based_on_version\":" + baseVersion + ",");
    }

    private static String treeWithSaveKind(String defaultValue, String saveKind) {
        return tree(defaultValue).replaceFirst("^\\{",
                "{\"save_kind\":\"" + saveKind + "\",");
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
                        .content(treeWithBase("42", currentVersion(templateId, "templates"))))
                .andExpect(status().isOk());

        assertThat(versionsOf(templateId)).hasSize(2);
        assertThat(versionsOf(templateId).get(0).getVersionNo()).isEqualTo(2);
    }

    @Test
    void restoringTemplate_returnsPreviousContentAndAppendsVersion() throws Exception {
        long templateId = createTemplate(tree("10"));
        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(treeWithBase("42", currentVersion(templateId, "templates"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/editor/templates/" + templateId + "/restore/1")
                        .header("X-Username", USER))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/editor/templates/" + templateId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode template = objectMapper.readTree(body);

        assertThat(template.get("rootComponent").get("properties").get(0)
                .get("default_value").asText()).isEqualTo("10");
        assertThat(versionsOf(templateId)).hasSize(3);
        assertThat(versionsOf(templateId).get(0).getKind()).isEqualTo(VersionKind.RESTORE);
        assertThat(versionsOf(templateId).get(0).getRestoredFrom()).isEqualTo(1);
    }

    @Test
    void updatingTemplateWithoutChanges_createsNoVersion() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(treeWithBase("10", currentVersion(templateId, "templates"))))
                .andExpect(status().isOk());

        assertThat(versionsOf(templateId)).hasSize(1);
    }

    @Test
    void templateResponseCarriesVersionNo() throws Exception {
        long templateId = createTemplate(tree("10"));

        String body = mockMvc.perform(get("/api/editor/templates/" + templateId + "/versions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get(0).get("version_no").asInt()).isEqualTo(1);
    }

    @Test
    void updatingTemplateWithoutBaseVersion_is400() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tree("20")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatingTemplateWithStaleBase_is409() throws Exception {
        long templateId = createTemplate(tree("10"));

        String body = mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(treeWithBase("20", 99)))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("error").asText()).isEqualTo("version_mismatch");
    }

    @Test
    void updatingTemplateWithMatchingBase_passes() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(treeWithBase("20", 1)))
                .andExpect(status().isOk());
    }

    /**
     * {@code RESTORE} — kind, который ставит только сервер при восстановлении версии; клиенту
     * через тело недоступен. Иначе {@code save_kind=RESTORE} стал бы лазейкой в обход проверки
     * {@code based_on_version} — см. аналогичный тест для сцен, {@code SaveEnvelopeIT}.
     */
    @Test
    void restoreSaveKindIsRejected() throws Exception {
        long templateId = createTemplate(tree("10"));

        mockMvc.perform(put("/api/editor/templates/" + templateId)
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(treeWithSaveKind("20", "RESTORE")))
                .andExpect(status().isBadRequest());
    }
}
