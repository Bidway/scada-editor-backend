package com.example.editor.support;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InfraSmokeIT extends EditorApiTestSupport {

    @Test
    void createsProjectAndScene() throws Exception {
        long projectId = createProject("проверка");
        long sceneId = createScene("сцена", projectId);
        assertThat(projectId).isPositive();
        assertThat(sceneId).isPositive();
    }
}
