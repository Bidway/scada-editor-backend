package com.example.runtime.automation;

import com.example.runtime.automation.engine.TaskStatusUpdate;
import com.example.runtime.automation.store.AutomationStore;
import com.example.runtime.kafka.KafkaTagMessageEvent;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.VariableTags;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Переменные и статусы фоновых задач → мониторы. Заменяет топик automation.state: пока задачи
 * жили в другом сервисе, это был мост через Kafka; теперь они в том же процессе.
 * <ul>
 *   <li>Переменная уходит тем же путём, что телеметрия: событием {@code var:<projectId>:<имя>}
 *       в TagValueRouter. Подписка, снимок, кадр и onChange работают без отдельного кода.</li>
 *   <li>Статус уходит в {@code UPDATE.tasks} мониторам проекта, подписанным на задачи.</li>
 * </ul>
 */
@Component
@Slf4j
public class AutomationStateBridge {

    private final ApplicationEventPublisher events;
    private final RuntimeSessionStore sessions;
    private final ObjectMapper mapper;
    private final AutomationStore store;

    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Map<String, Object>>> statuses = new ConcurrentHashMap<>();

    public AutomationStateBridge(ApplicationEventPublisher events, RuntimeSessionStore sessions,
                                 ObjectMapper mapper, AutomationStore store) {
        this.events = events;
        this.sessions = sessions;
        this.mapper = mapper;
        this.store = store;
    }

    public void publishVariable(long projectId, String name, Object value) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("value", value);
        envelope.put("quality", "GOOD");
        envelope.put("timestamp", Instant.now().toString());
        String key = "var:" + projectId + ":" + name;
        try {
            String raw = mapper.writeValueAsString(envelope);
            variables.put(key, raw);
            events.publishEvent(new KafkaTagMessageEvent(key, raw));
        } catch (Exception e) {
            log.warn("Переменная {} не опубликована: {}", key, e.getMessage());
        }
    }

    public void publishStatus(TaskStatusUpdate update, String owner) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("taskId", update.taskId());
        status.put("name", update.name());
        status.put("state", update.state().name());
        status.put("lastRunAt", update.lastRunAtMs());
        status.put("lastDurationMs", update.lastDurationMs());
        status.put("lastLagMs", update.lastLagMs());
        status.put("lastError", update.lastError());
        status.put("errorCount", update.errorCount());
        status.put("owner", owner);
        statuses.computeIfAbsent(update.projectId(), id -> new ConcurrentHashMap<>()).put(update.taskId(), status);
        for (RuntimeSession session : sessions.all()) {
            if (Long.valueOf(update.projectId()).equals(session.getProjectId()) && session.isTasksSubscribed()) {
                session.getOutboundBuffer().offerTask(status);
            }
        }
    }

    /** Отдать подключившемуся монитору текущие значения переменных его проекта. */
    public void replayVariables(RuntimeSession session) {
        for (String tagId : session.getIndex().getAllTagIds()) {
            if (VariableTags.isVariableKey(tagId)) {
                String raw = variables.get(tagId);
                if (raw != null) {
                    events.publishEvent(new KafkaTagMessageEvent(tagId, raw));
                }
            }
        }
    }

    /**
     * Статусы задач проекта. После перезапуска runtime в памяти их нет до первого такта — тогда
     * берутся последние записанные в базу, чтобы панель «Задачи» не открывалась пустой.
     */
    public List<Map<String, Object>> statusesOf(Long projectId) {
        Map<Long, Map<String, Object>> known = statuses.get(projectId);
        if (known != null && !known.isEmpty()) {
            return List.copyOf(known.values());
        }
        try {
            return store.statuses(projectId).stream().map(row -> {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("taskId", row.taskId());
                status.put("name", row.name());
                status.put("state", row.state());
                status.put("lastRunAt", row.lastRunAtMs());
                status.put("lastDurationMs", row.lastDurationMs());
                status.put("lastLagMs", row.lastLagMs());
                status.put("lastError", row.lastError());
                status.put("errorCount", row.errorCount());
                status.put("owner", row.ownerInstance());
                return status;
            }).toList();
        } catch (Exception e) {
            log.warn("Статусы задач проекта {} не прочитаны из базы: {}", projectId, e.getMessage());
            return List.of();
        }
    }
}
