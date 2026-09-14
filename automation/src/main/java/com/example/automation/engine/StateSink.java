package com.example.automation.engine;

import com.example.automation.config.AutomationProperties;
import com.example.automation.kafka.StatePublisher;
import com.example.automation.store.AutomationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Результаты тактов → база и {@code automation.state}.
 * <ul>
 *   <li>Контрольные точки и переменные копятся и раз в {@code checkpoint-flush-ms} уходят одним
 *       батчем по одной строке на ключ. База недоступна — строки остаются и уйдут следующим проходом;
 *       задачи тем временем работают из памяти.</li>
 *   <li>Статус публикуется и пишется при смене состояния или ошибки, иначе не чаще
 *       {@code status-refresh-ms}: такт раз в секунду не должен давать запись раз в секунду.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StateSink implements TaskObserver {

    private final AutomationStore store;
    private final StatePublisher publisher;
    private final AutomationProperties properties;

    private final Map<String, AutomationStore.CheckpointRow> checkpoints = new HashMap<>();
    private final Map<String, AutomationStore.VariableRow> variables = new HashMap<>();
    private final Map<String, AutomationStore.StatusRow> statuses = new HashMap<>();
    private final Map<String, TaskStatusUpdate> lastPublished = new HashMap<>();
    private long lastFailureLogMs;

    @Override
    public void status(TaskStatusUpdate update) {
        String key = update.projectId() + ":" + update.taskId();
        synchronized (this) {
            TaskStatusUpdate previous = lastPublished.get(key);
            boolean changed = previous == null || previous.state() != update.state()
                    || !Objects.equals(previous.lastError(), update.lastError());
            boolean refresh = previous != null && previous.lastRunAtMs() != null && update.lastRunAtMs() != null
                    && update.lastRunAtMs() - previous.lastRunAtMs() >= properties.getEngine().getStatusRefreshMs();
            if (!changed && !refresh) {
                return;
            }
            lastPublished.put(key, update);
            statuses.put(key, new AutomationStore.StatusRow(update.projectId(), update.taskId(), update.name(),
                    update.state().name(), update.lastRunAtMs(), update.lastDurationMs(), update.lastError(),
                    update.errorCount(), properties.getInstanceId()));
        }
        publisher.publishStatus(update, properties.getInstanceId());
    }

    @Override
    public synchronized void checkpoint(long projectId, long epoch, long taskId, String definitionHash,
                                        Map<String, Object> state) {
        checkpoints.put(projectId + ":" + taskId,
                new AutomationStore.CheckpointRow(projectId, taskId, epoch, definitionHash, state));
    }

    @Override
    public void variable(long projectId, long epoch, String name, Object value) {
        synchronized (this) {
            variables.put(projectId + ":" + name, new AutomationStore.VariableRow(projectId, name, value, epoch));
        }
        publisher.publishVariable(projectId, name, value);
    }

    @Scheduled(fixedDelayString = "${automation.engine.checkpoint-flush-ms:1000}")
    public void flush() {
        flush(null);
    }

    @Override
    public void flushProject(long projectId) {
        flush(projectId);
    }

    private void flush(Long projectId) {
        List<AutomationStore.CheckpointRow> checkpointRows;
        List<AutomationStore.VariableRow> variableRows;
        List<AutomationStore.StatusRow> statusRows;
        synchronized (this) {
            checkpointRows = take(checkpoints, projectId, AutomationStore.CheckpointRow::projectId);
            variableRows = take(variables, projectId, AutomationStore.VariableRow::projectId);
            statusRows = take(statuses, projectId, AutomationStore.StatusRow::projectId);
        }
        if (checkpointRows.isEmpty() && variableRows.isEmpty() && statusRows.isEmpty()) {
            return;
        }
        try {
            if (!checkpointRows.isEmpty()) {
                store.saveCheckpoints(checkpointRows);
            }
            if (!variableRows.isEmpty()) {
                store.saveVariables(variableRows);
            }
            if (!statusRows.isEmpty()) {
                store.saveStatuses(statusRows);
            }
        } catch (Exception e) {
            synchronized (this) {
                // Вернуть несохранённое, не затирая то, что пришло новее за время попытки.
                checkpointRows.forEach(row -> checkpoints.putIfAbsent(row.projectId() + ":" + row.taskId(), row));
                variableRows.forEach(row -> variables.putIfAbsent(row.projectId() + ":" + row.name(), row));
                statusRows.forEach(row -> statuses.putIfAbsent(row.projectId() + ":" + row.taskId(), row));
                long now = System.currentTimeMillis();
                if (now - lastFailureLogMs > 30_000) {
                    lastFailureLogMs = now;
                    log.warn("Automation state not saved, tasks keep running from memory: {}", e.getMessage());
                }
            }
        }
    }

    private static <T> List<T> take(Map<String, T> rows, Long projectId, ToLongFunction<T> projectOf) {
        List<T> taken = new ArrayList<>();
        Iterator<Map.Entry<String, T>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            T row = iterator.next().getValue();
            if (projectId == null || projectOf.applyAsLong(row) == projectId) {
                taken.add(row);
                iterator.remove();
            }
        }
        return taken;
    }
}
