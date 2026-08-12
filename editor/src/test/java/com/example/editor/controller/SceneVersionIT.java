package com.example.editor.controller;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
