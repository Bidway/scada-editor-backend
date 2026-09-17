package com.example.runtime.automation;

import com.example.runtime.automation.engine.TaskState;
import com.example.runtime.automation.engine.TaskStatusUpdate;
import com.example.runtime.automation.store.AutomationStore;
import com.example.runtime.kafka.KafkaTagMessageEvent;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagSubscriptionIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Мост заменяет топик automation.state: переменные и статусы задач больше не ходят через Kafka,
 * но фронт получает их в том же виде — @var.* тегом, статус в UPDATE.tasks.
 */
class AutomationStateBridgeTest {

    @Test
    void переменная_уходит_тегом_а_статус_подписанному_монитору() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        TagSubscriptionIndex index = mock(TagSubscriptionIndex.class);
        when(index.getAllTagIds()).thenReturn(Set.of());
        when(index.getInitialPropertyValues()).thenReturn(Map.of());
        RuntimeSessionStore sessions = new RuntimeSessionStore();
        RuntimeSession subscribed = new RuntimeSession("s1", new ProjectRuntime(8501L, index, null));
        subscribed.setTasksSubscribed(true);
        sessions.put(subscribed);
        AutomationStateBridge bridge = new AutomationStateBridge(events, sessions, new ObjectMapper(),
                mock(AutomationStore.class));

        bridge.publishVariable(8501L, "line1.mode", 2);
        bridge.publishStatus(new TaskStatusUpdate(8501L, 12L, "ПИД", TaskState.RUNNING, 1000L, 3L, null, 0L, 7L),
                "runtime-1");

        ArgumentCaptor<KafkaTagMessageEvent> event = ArgumentCaptor.forClass(KafkaTagMessageEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().key()).isEqualTo("var:8501:line1.mode");
        assertThat(event.getValue().rawValue()).contains("\"value\":2").contains("\"quality\":\"GOOD\"");
        assertThat(subscribed.getOutboundBuffer().drainAll().tasks()).singleElement()
                .satisfies(status -> {
                    assertThat(status.get("taskId")).isEqualTo(12L);
                    assertThat(status.get("lastLagMs")).isEqualTo(7L);
                });
        assertThat(bridge.statusesOf(8501L)).hasSize(1);
    }
}
