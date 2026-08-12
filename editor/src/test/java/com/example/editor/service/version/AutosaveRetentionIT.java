package com.example.editor.service.version;

import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.version.DocumentVersionRepository;
import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прореживание старых автосохранений. Ручные сохранения не трогаются никогда: их за смену
 * единицы, и именно они — осознанные точки, к которым человек захочет вернуться.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutosaveRetentionIT extends EditorApiTestSupport {

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private AutosaveRetentionJob retentionJob;

    private void version(long targetId, int no, VersionKind kind, LocalDateTime createdAt) {
        DocumentVersion v = new DocumentVersion();
        v.setTargetType(DocumentType.SCENE);
        v.setTargetId(targetId);
        v.setVersionNo(no);
        v.setKind(kind);
        v.setContent(objectMapper.createObjectNode().put("no", no));
        v.setContentHash("hash-" + targetId + "-" + no);
        v.setUserName(USER);
        v.setCreatedAt(createdAt);
        versionRepository.saveAndFlush(v);
    }

    @Test
    void oldAutosaves_areThinnedToOnePerDay() throws Exception {
        long sceneId = newScene();
        LocalDateTime longAgo = LocalDateTime.now().minusDays(90);
        version(sceneId, 1, VersionKind.AUTOSAVE, longAgo.withHour(9));
        version(sceneId, 2, VersionKind.AUTOSAVE, longAgo.withHour(12));
        version(sceneId, 3, VersionKind.AUTOSAVE, longAgo.withHour(18));
        version(sceneId, 4, VersionKind.MANUAL, longAgo.withHour(19));
        version(sceneId, 5, VersionKind.AUTOSAVE, LocalDateTime.now());

        retentionJob.thinOut();

        assertThat(versionRepository
                .findByTargetTypeAndTargetIdOrderByVersionNoDesc(DocumentType.SCENE, sceneId))
                .extracting(DocumentVersion::getVersionNo)
                .as("из трёх старых автосохранений одного дня остаётся последнее; ручное и "
                        + "свежее автосохранение не трогаются")
                .containsExactlyInAnyOrder(3, 4, 5);
    }
}
