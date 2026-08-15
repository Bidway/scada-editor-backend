package com.example.editor.controller;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.SceneDocumentSource;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Снимки сцен. История — append-only: номера версий не убывают, восстановление дописывает
 * новую версию, а не переписывает прежние.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneVersionIT extends EditorApiTestSupport {

    @Autowired
    protected DocumentVersionRepository versionRepository;

    @Autowired
    private DocumentVersionService versionService;

    @Autowired
    private SceneDocumentSource sceneDocumentSource;

    private DocumentVersion version(long targetId, int no) {
        DocumentVersion v = new DocumentVersion();
        v.setTargetType(DocumentType.SCENE);
        v.setTargetId(targetId);
        v.setVersionNo(no);
        v.setKind(VersionKind.MANUAL);
        v.setContent(objectMapper.createObjectNode().put("no", no));
        v.setContentHash("hash-" + no);
        v.setUserName(USER);
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    @Test
    void versionNoIsUniqueWithinDocument() throws Exception {
        long sceneId = newScene();
        versionRepository.saveAndFlush(version(sceneId, 1));

        assertThatThrownBy(() -> versionRepository.saveAndFlush(version(sceneId, 1)))
                .as("два снимка с одним номером у одного документа — это потерянная история")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lastVersionOfDocumentIsFound() throws Exception {
        long sceneId = newScene();
        versionRepository.saveAndFlush(version(sceneId, 1));
        versionRepository.saveAndFlush(version(sceneId, 2));

        assertThat(versionRepository
                .findTopByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId))
                .get()
                .extracting(DocumentVersion::getVersionNo)
                .isEqualTo(2);
    }

    private JsonNode content(String name) {
        return objectMapper.createObjectNode().put("name", name);
    }

    @Test
    void recordingSameContentTwice_createsOneVersion() throws Exception {
        long sceneId = newScene();

        versionService.record(DocumentType.SCENE, sceneId, content("A"), USER,
                VersionKind.MANUAL, null);
        DocumentVersion second = versionService.record(DocumentType.SCENE, sceneId, content("A"),
                USER, VersionKind.MANUAL, null);

        assertThat(second.getVersionNo())
                .as("содержимое не изменилось — новой версии быть не должно")
                .isEqualTo(1);
        assertThat(versionRepository
                .findByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId))
                .hasSize(1);
    }

    @Test
    void recordingChangedContent_incrementsVersionNo() throws Exception {
        long sceneId = newScene();

        versionService.record(DocumentType.SCENE, sceneId, content("A"), USER,
                VersionKind.MANUAL, null);
        DocumentVersion second = versionService.record(DocumentType.SCENE, sceneId, content("B"),
                USER, VersionKind.MANUAL, null);

        assertThat(second.getVersionNo()).isEqualTo(2);
        assertThat(second.getContentHash()).isNotEqualTo(
                versionRepository.findByTargetTypeAndTargetIdAndVersionNo(
                        DocumentType.SCENE, sceneId, 1).orElseThrow().getContentHash());
    }

    @Test
    void sceneContent_isTheWholeTreeWithIds() throws Exception {
        long sceneId = newScene();
        JsonNode component = saveComponents("[{\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\"}]}]").get(0);
        long componentId = component.get("id").asLong();

        JsonNode content = sceneDocumentSource.contentOf(sceneId);

        assertThat(content.get("id").asLong()).isEqualTo(sceneId);
        assertThat(content.get("children")).hasSize(1);
        JsonNode child = content.get("children").get(0);
        assertThat(child.get("id").asLong())
                .as("снимок берётся из ответа API — с проставленными id, иначе восстановление "
                        + "не сможет вернуть те же id")
                .isEqualTo(componentId);
        assertThat(child.get("properties").get(0).get("name").asText()).isEqualTo("Уставка");
    }

    private void saveComponentsAs(String kind, String json) throws Exception {
        saveComponents(json, null, kind);
    }

    private String pumpJson(long sceneId, String setpoint) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"" + setpoint + "\"}]}]";
    }

    private List<DocumentVersion> versionsOf(long sceneId) {
        return versionRepository.findByTargetTypeAndTargetIdOrderByVersionNoDesc(
                DocumentType.SCENE, sceneId);
    }

    @Test
    void savingComponent_createsManualVersionOfItsScene() throws Exception {
        long sceneId = newScene();

        saveComponents(pumpJson(sceneId, "10"));

        List<DocumentVersion> versions = versionsOf(sceneId);
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getKind()).isEqualTo(VersionKind.MANUAL);
        assertThat(versions.get(0).getUserName()).isEqualTo(USER);
        assertThat(versions.get(0).getContent().get("children")).hasSize(1);
    }

    @Test
    void autosaveHeader_marksVersionAsAutosave() throws Exception {
        long sceneId = newScene();

        saveComponentsAs("AUTOSAVE", pumpJson(sceneId, "10"));

        assertThat(versionsOf(sceneId))
                .singleElement()
                .extracting(DocumentVersion::getKind)
                .isEqualTo(VersionKind.AUTOSAVE);
    }

    @Test
    void deletingComponent_createsVersionOfItsScene() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        Integer base = currentVersion(sceneId, "scenes");

        mockMvc.perform(delete("/api/editor/components")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + componentId + "],\"based_on_version\":" + base + "}"))
                .andExpect(status().isOk());

        List<DocumentVersion> versions = versionsOf(sceneId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getContent().get("children"))
                .as("удаление компонента — такое же изменение сцены, как правка")
                .isEmpty();
    }

    @Test
    void resavingWithoutChanges_doesNotCreateVersion() throws Exception {
        long sceneId = newScene();
        JsonNode created = saveComponents(pumpJson(sceneId, "10")).get(0);
        long componentId = created.get("id").asLong();
        int before = versionsOf(sceneId).size();

        updateScene(sceneId, "[{\"id\":" + componentId + ",\"name\":\"Насос\",\"type\":\"valve\","
                + "\"parent_id\":" + sceneId + ",\"properties\":[{\"name\":\"Уставка\","
                + "\"value_type\":\"double\",\"property_type\":\"Тег\","
                + "\"default_value\":\"10\"}]}]", currentVersion(sceneId, "scenes"));

        assertThat(versionsOf(sceneId))
                .as("содержимое то же — версии быть не должно; счётчик @Version, растущий на "
                        + "каждом сохранении, в хеш не входит")
                .hasSize(before);
    }

    @Test
    void versionsOfDifferentDocuments_areNumberedIndependently() throws Exception {
        long firstScene = newScene();
        long secondScene = newScene();

        versionService.record(DocumentType.SCENE, firstScene, content("A"), USER,
                VersionKind.MANUAL, null);
        DocumentVersion other = versionService.record(DocumentType.SCENE, secondScene,
                content("A"), USER, VersionKind.MANUAL, null);

        assertThat(other.getVersionNo())
                .as("номер версии — порядковый в пределах документа, а не сквозной")
                .isEqualTo(1);
    }
}
