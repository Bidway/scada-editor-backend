package com.example.runtime.automation.engine;

import com.example.runtime.automation.AutomationEngineProperties;
import com.example.runtime.automation.definition.ProjectDefinitions;
import com.example.runtime.automation.definition.TaskDefinition;
import com.example.runtime.automation.store.AutomationStore;
import com.example.runtime.kafka.CommandOutcome;
import com.example.scriptcore.ProjectData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Задачи живут вместе с проектом: поднятый проект с определениями их исполняет, погашенный —
 * останавливает и сбрасывает память задач в базу. Определения могут прийти раньше или позже
 * подъёма проекта — порядок не должен иметь значения.
 */
class AutomationEngineTest {

    private final AtomicBoolean projectUp = new AtomicBoolean();
    private final TaskObserver observer = mock(TaskObserver.class);
    private AutomationEngine engine;

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    void задачи_запускаются_с_проектом_и_сбрасываются_при_гашении() {
        AutomationStore store = mock(AutomationStore.class);
        when(store.loadVariables(anyLong())).thenReturn(Map.of());
        when(store.loadCheckpoint(anyLong(), anyLong())).thenReturn(Optional.empty());
        CommandSender commands = new CommandSender() {
            @Override
            public CompletableFuture<CommandOutcome> send(String tag, Object value) {
                return CompletableFuture.completedFuture(CommandOutcome.applied("ok"));
            }

            @Override
            public long lastResultAtMs() {
                return 0;
            }
        };
        engine = new AutomationEngine(new AutomationEngineProperties(), new TagCache(), commands, observer,
                store, new ObjectMapper(), projectId -> ProjectData.EMPTY, projectId -> projectUp.get());

        TaskDefinition task = new TaskDefinition(1L, "ПИД", true, 1000, 500, 5000, false,
                List.of(), List.of(), List.of(), "state = {};");
        engine.definitionsChanged(8501L, new ProjectDefinitions(8501L, 1, List.of(task), List.of(), null));
        assertThat(engine.isRunning(8501L)).isFalse();   // определения есть, проект не поднят

        projectUp.set(true);
        engine.projectActivated(8501L);
        assertThat(engine.isRunning(8501L)).isTrue();

        projectUp.set(false);
        engine.projectDeactivated(8501L);
        assertThat(engine.isRunning(8501L)).isFalse();
        verify(observer).flushProject(8501L);
    }
}
