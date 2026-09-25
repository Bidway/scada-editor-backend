package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * scada-fqr: сохранение сцены рассылает SCENE_CHANGED подписчикам /topic/scenes/{id}; повтор без
 * изменений новой версии не создаёт — и уведомления тоже нет.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SceneChangedNotificationIT extends EditorApiTestSupport {

    @MockitoSpyBean
    private SimpMessagingTemplate messaging;

    @Test
    @SuppressWarnings("unchecked")
    void sceneSave_notifiesSubscribers_andUnchangedResaveDoesNot() throws Exception {
        long sceneId = newScene();
        String component = "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + "}]";
        clearInvocations(messaging);

        saveComponents(component);

        var payload = forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/scenes/" + sceneId), payload.capture());
        Map<String, Object> message = (Map<String, Object>) payload.getValue();
        assertThat(message).containsEntry("type", "SCENE_CHANGED").containsEntry("scene_id", sceneId)
                .containsEntry("user_name", USER).containsKey("version_no");

        clearInvocations(messaging);
        Integer current = currentVersion(sceneId, "scenes");
        updateScene(sceneId, getComponent(sceneId).get("children").toString(), current);
        verify(messaging, never()).convertAndSend(eq("/topic/scenes/" + sceneId), any(Object.class));
    }
}
