package com.example.runtime.session;

import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.script.ActionDedupGuard;
import com.example.runtime.script.ScriptEngineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Выключенный проект обязан отпустить своих наблюдателей. Сессия держит ссылку на объект
 * проекта, а повторное включение создаёт новый: без закрытия монитор оставался подключён к
 * мёртвому объекту и молча замирал (scada-m6mh).
 */
class RuntimeSessionServiceTest {

    @Test
    void выключение_проекта_закрывает_его_сессии_с_причиной() throws Exception {
        TagSubscriptionIndex index = mock(TagSubscriptionIndex.class);
        when(index.getAllTagIds()).thenReturn(Set.of("Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST"));
        when(index.getInitialPropertyValues()).thenReturn(Map.of());
        ProjectRuntime project = new ProjectRuntime(8501L, index, null);
        ProjectRuntime otherProject = new ProjectRuntime(7L, index, null);

        RuntimeSessionStore sessions = new RuntimeSessionStore();
        RuntimeSession watching = new RuntimeSession("watching", project);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        watching.setWebSocketSession(ws);
        project.addObserver(watching);
        sessions.put(watching);
        // Создана по REST, но WS ещё не подключён: подключись она позже — попала бы в мёртвый объект.
        RuntimeSession notConnected = new RuntimeSession("not-connected", project);
        sessions.put(notConnected);
        RuntimeSession foreign = new RuntimeSession("foreign", otherProject);
        sessions.put(foreign);

        RuntimeSessionService service = new RuntimeSessionService(sessions, mock(ProjectRuntimeStore.class),
                mock(TagValueRouter.class), mock(ScriptEngineService.class), mock(TagCommandService.class),
                mock(ActionDedupGuard.class),
                new com.example.runtime.instance.InstanceIdentity("test", "http://localhost:8085"));

        service.closeSessionsOf(project);

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(ws).close(status.capture());
        assertThat(status.getValue().getReason()).contains("выведен из эксплуатации");
        assertThat(sessions.get("watching")).isNull();
        assertThat(sessions.get("not-connected")).isNull();
        assertThat(sessions.get("foreign")).isNotNull();
        assertThat(project.sessions()).isEmpty();
    }
}
