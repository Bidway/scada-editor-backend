package com.example.runtime.automation.engine;

import com.example.runtime.kafka.CommandOutcome;
import com.example.runtime.automation.definition.IoDefinition;
import com.example.runtime.automation.definition.TaskDefinition;
import com.example.scriptcore.ProjectData;
import com.example.scriptcore.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRunnerTest {

    private static SandboxExecutor scripts;

    private final List<Object> sent = new ArrayList<>();
    private final List<TaskStatusUpdate> statuses = new ArrayList<>();
    private final List<Map<String, Object>> checkpoints = new ArrayList<>();
    private final Map<String, TagReading> tags = new HashMap<>();
    private ProjectData projectData = ProjectData.EMPTY;

    @BeforeAll
    static void startScripts() {
        scripts = new SandboxExecutor(1);
    }

    @AfterAll
    static void stopScripts() {
        scripts.close();
    }

    /**
     * Опоздание начала такта относительно расписания — метрика, по которой видно, что соседи
     * по процессу (мониторы, onChange) мешают регулятору.
     */
    @Test
    void опоздание_такта_попадает_в_статус() {
        TaskRunner runner = runner(List.of(), "state = {};", 10_000);

        runner.tick(9_750L);

        assertEquals(250L, statuses.get(statuses.size() - 1).lastLagMs());
    }

    /** Упавший такт не портит память регулятора и не отправляет в насос полуготовое значение. */
    @Test
    void failedTickAppliesNothing() {
        TaskRunner runner = runner(List.of(),
                "write('U', 1); state = { x: 1 }; throw new Error('boom');", 5000);

        runner.tick();

        assertTrue(sent.isEmpty());
        assertTrue(checkpoints.isEmpty());
        assertEquals(TaskState.ERROR, statuses.get(statuses.size() - 1).state());
    }

    /** По мёртвым данным регулятор не считает: скрипт не запускается. */
    @Test
    void staleInputSkipsScript() {
        tags.put("FLOW", new TagReading(10.0, true, 0L));
        TaskRunner runner = runner(List.of(new IoDefinition("F", "FLOW", "float")), "write('U', 1);", 5000);

        runner.tick();

        assertTrue(sent.isEmpty());
        assertEquals(TaskState.INPUT_STALE, statuses.get(statuses.size() - 1).state());
    }

    /** editor лежал при запуске проекта: задача со справочником стоит в ошибке с причиной, а не молчит. */
    @Test
    void dataNotLoadedFailsTickWithReason() {
        projectData = null;
        TaskRunner runner = runner(List.of(), "write('U', data('solutions', 'ALK').density);", 5000);

        runner.tick();

        assertTrue(sent.isEmpty());
        TaskStatusUpdate last = statuses.get(statuses.size() - 1);
        assertEquals(TaskState.ERROR, last.state());
        assertTrue(last.lastError().contains("data(): данные проекта не загружены"), last.lastError());
    }

    private TaskRunner runner(List<IoDefinition> inputs, String script, long nowMs) {
        TaskDefinition definition = new TaskDefinition(1L, "pid", true, 1000, 500, 1000, false,
                inputs, List.of(new IoDefinition("U", "PUMP.V", "float")), List.of(), script);
        CommandSender sender = new CommandSender() {
            @Override
            public CompletableFuture<CommandOutcome> send(String tag, Object value) {
                sent.add(value);
                return CompletableFuture.completedFuture(new CommandOutcome(true, "APPLIED", null));
            }

            @Override
            public long lastResultAtMs() {
                return 0;
            }
        };
        TaskObserver observer = new TaskObserver() {
            @Override
            public void status(TaskStatusUpdate update) {
                statuses.add(update);
            }

            @Override
            public void checkpoint(long projectId, long taskId, String hash, Map<String, Object> state) {
                checkpoints.add(state);
            }

            @Override
            public void variable(long projectId, String name, Object value) {
            }
        };
        return new TaskRunner(7L, definition, "hash", null, scripts, tags::get,
                new OutputWriter(sender), new VariableBoard(Map.of()), () -> projectData, observer, () -> true,
                () -> nowMs, new ObjectMapper());
    }
}
