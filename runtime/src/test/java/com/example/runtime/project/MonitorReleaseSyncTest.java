package com.example.runtime.project;

import com.example.runtime.automation.AutomationStateBridge;
import com.example.runtime.instance.InstanceIdentity;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.recipe.ProcedureExecutionService;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionService;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.runtime.ws.RuntimeWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Монитор получил дерево выпуска 1 в ответе POST /sessions, а до подключения WS проект перешёл на
 * выпуск 2. Событие смены такую сессию пропускает (соединения ещё нет) — значит, подключение само
 * должно сказать монитору TREE_CHANGED, иначе экран остался бы на сценах выпуска 1 со значениями 2.
 * <p>
 * Тест лежит в пакете project: replaceModel пакетный.
 */
class MonitorReleaseSyncTest {

    @Test
    void подключение_после_смены_выпуска_сначала_шлёт_TREE_CHANGED() throws Exception {
        ProjectRuntime project = new ProjectRuntime(8501L,
                new ProjectModel(1, null, null, mock(TagSubscriptionIndex.class)), null);
        ProjectRuntimeStore store = new ProjectRuntimeStore();
        store.put(project);
        RuntimeSession session = new RuntimeSession("runtime-1.abc", project);
        project.replaceModel(new ProjectModel(2, null, null, mock(TagSubscriptionIndex.class)));

        RuntimeSessionService sessions = mock(RuntimeSessionService.class);
        when(sessions.getSession("runtime-1.abc")).thenReturn(session);
        RuntimeWebSocketHandler handler = new RuntimeWebSocketHandler(sessions, new ObjectMapper(),
                mock(AutomationStateBridge.class), mock(TagValueRouter.class), mock(ProcedureExecutionService.class),
                store, new InstanceIdentity("runtime-1", "http://runtime-1:8085"));

        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getUri()).thenReturn(URI.create("ws://h/ws/runtime/runtime-1/runtime-1.abc"));
        when(ws.isOpen()).thenReturn(true);
        List<String> sent = new ArrayList<>();
        doAnswer(inv -> sent.add(((TextMessage) inv.getArgument(0, WebSocketMessage.class)).getPayload()))
                .when(ws).sendMessage(any());

        handler.afterConnectionEstablished(ws);

        assertThat(sent).hasSizeGreaterThanOrEqualTo(2);
        assertThat(sent.get(0)).contains("\"type\":\"TREE_CHANGED\"").contains("\"versionNo\":2");
        assertThat(sent.get(1)).contains("\"type\":\"SNAPSHOT\"");
    }
}
