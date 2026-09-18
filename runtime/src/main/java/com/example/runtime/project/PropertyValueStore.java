package com.example.runtime.project;

import com.example.runtime.instance.InstanceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Значения свойств проекта, которые пишут скрипты, — в {@code runtime.property_value}, чтобы они
 * пережили перезапуск runtime (scada-vrkf). Без этого редко меняющиеся свойства (режим, выбранный
 * рецепт, флаги скриптов) после рестарта показывали {@code default_value}, а условия шагов
 * процедур читали default вместо реального.
 * <p>
 * Запись не синхронная: изменения копятся и раз в {@code runtime.property-values.flush-ms}
 * уходят одним проходом — по строке на свойство, так что частая перезапись одного свойства
 * даёт одну запись. Тот же приём и то же условие владения, что у {@code AutomationStore}:
 * экземпляр, у которого проект сняли, пока он висел, ничего не допишет.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyValueStore {

    /** Проект всё ещё назначен этому экземпляру. Параметры: project_id, instance_id. */
    private static final String OWNED = " AND EXISTS (SELECT 1 FROM runtime.instance_project ip "
            + "WHERE ip.project_id = ? AND ip.instance_id = ?)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InstanceIdentity identity;

    /** Ключ — projectId:propertyId; значение null — свойство сброшено, строку удалить. */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long lastFailureLogMs;

    private record Pending(long projectId, long propertyId, Object value) {
    }

    /** Отметить изменение; в базу оно уйдёт ближайшим сбросом. */
    public synchronized void record(long projectId, long propertyId, Object value) {
        pending.put(projectId + ":" + propertyId, new Pending(projectId, propertyId, value));
    }

    /** Сохранённые значения проекта — для подъёма после перезапуска. */
    public Map<Long, Object> load(long projectId) {
        Map<Long, Object> values = new HashMap<>();
        jdbc.query("SELECT property_id, value::text FROM runtime.property_value WHERE project_id = ?",
                rs -> {
                    try {
                        values.put(rs.getLong(1), mapper.readValue(rs.getString(2), Object.class));
                    } catch (Exception e) {
                        log.warn("Проект {}: значение свойства {} не прочитано: {}",
                                projectId, rs.getLong(1), e.getMessage());
                    }
                }, projectId);
        return values;
    }

    @Scheduled(fixedDelayString = "${runtime.property-values.flush-ms:1000}")
    public void flush() {
        flush(null);
    }

    /** Сбросить сразу накопленное по проекту — перед его выключением. */
    public void flushProject(long projectId) {
        flush(projectId);
    }

    private void flush(Long projectId) {
        List<Pending> taken = new ArrayList<>();
        synchronized (this) {
            pending.entrySet().removeIf(entry -> {
                if (projectId == null || entry.getValue().projectId() == projectId) {
                    taken.add(entry.getValue());
                    return true;
                }
                return false;
            });
        }
        if (taken.isEmpty()) {
            return;
        }
        try {
            for (Pending row : taken) {
                write(row);
            }
        } catch (Exception e) {
            synchronized (this) {
                // Вернуть несохранённое, не затирая то, что пришло новее за время попытки.
                taken.forEach(row -> pending.putIfAbsent(row.projectId() + ":" + row.propertyId(), row));
                long now = System.currentTimeMillis();
                if (now - lastFailureLogMs > 30_000) {
                    lastFailureLogMs = now;
                    log.warn("Значения свойств не сохранены, повтор следующим проходом: {}", e.getMessage());
                }
            }
        }
    }

    private void write(Pending row) throws Exception {
        if (row.value() == null) {
            jdbc.update("DELETE FROM runtime.property_value WHERE project_id = ? AND property_id = ?" + OWNED,
                    row.projectId(), row.propertyId(), row.projectId(), identity.instanceId());
            return;
        }
        jdbc.update("""
                INSERT INTO runtime.property_value (project_id, property_id, value, updated_at)
                SELECT ?, ?, ?::jsonb, now() WHERE TRUE""" + OWNED + """

                ON CONFLICT (project_id, property_id) DO UPDATE
                SET value = EXCLUDED.value, updated_at = now()""",
                row.projectId(), row.propertyId(), mapper.writeValueAsString(row.value()),
                row.projectId(), identity.instanceId());
    }
}
