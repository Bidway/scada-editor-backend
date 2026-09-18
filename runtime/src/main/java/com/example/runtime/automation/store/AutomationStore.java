package com.example.runtime.automation.store;

import com.example.runtime.instance.InstanceIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Рабочее состояние фоновых задач в схеме {@code automation}. Записи идут только для проектов,
 * назначенных этому экземпляру ({@code runtime.instance_project}): экземпляр, у которого проект сняли,
 * пока он висел, ничего не допишет.
 */
@Component
@RequiredArgsConstructor
public class AutomationStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InstanceIdentity identity;

    /** Проект всё ещё назначен этому экземпляру. Параметры: project_id, instance_id. */
    private static final String OWNED = "WHERE EXISTS (SELECT 1 FROM runtime.instance_project ip "
            + "WHERE ip.project_id = ? AND ip.instance_id = ?)";

    public record CheckpointRow(long projectId, long taskId, String definitionHash, Map<String, Object> state) {
    }

    public record VariableRow(long projectId, String name, Object value) {
    }

    /** @param lastLagMs опоздание начала последнего такта относительно расписания, мс */
    public record StatusRow(long projectId, long taskId, String name, String state, Long lastRunAtMs,
                            Long lastDurationMs, String lastError, long errorCount, String ownerInstance,
                            Long lastLagMs) {
    }

    public Optional<CheckpointRow> loadCheckpoint(long projectId, long taskId) {
        return jdbc.query("""
                        SELECT definition_hash, state::text FROM automation.task_checkpoint
                        WHERE project_id = ? AND task_id = ?""",
                (rs, i) -> new CheckpointRow(projectId, taskId, rs.getString(1), readMap(rs.getString(2))),
                projectId, taskId).stream().findFirst();
    }

    public Map<String, Object> loadVariables(long projectId) {
        Map<String, Object> values = new HashMap<>();
        jdbc.query("SELECT name, value::text FROM automation.variable_value WHERE project_id = ?",
                rs -> {
                    values.put(rs.getString(1), readValue(rs.getString(2)));
                }, projectId);
        return values;
    }

    public void saveCheckpoints(List<CheckpointRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO automation.task_checkpoint (project_id, task_id, definition_hash, state, updated_at)
                SELECT ?, ?, ?, ?::jsonb, now()
                """ + OWNED + """

                ON CONFLICT (project_id, task_id) DO UPDATE
                SET definition_hash = EXCLUDED.definition_hash, state = EXCLUDED.state, updated_at = now()""",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.projectId());
                    ps.setLong(2, row.taskId());
                    ps.setString(3, row.definitionHash());
                    ps.setString(4, write(row.state()));
                    ps.setLong(5, row.projectId());
                    ps.setString(6, identity.instanceId());
                });
    }

    public void saveVariables(List<VariableRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO automation.variable_value (project_id, name, value, quality, updated_at)
                SELECT ?, ?, ?::jsonb, 'GOOD', now()
                """ + OWNED + """

                ON CONFLICT (project_id, name) DO UPDATE
                SET value = EXCLUDED.value, updated_at = now()""",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.projectId());
                    ps.setString(2, row.name());
                    ps.setString(3, write(row.value()));
                    ps.setLong(4, row.projectId());
                    ps.setString(5, identity.instanceId());
                });
    }

    /**
     * Удалить статусы, память и значения задач и переменных проекта, которых нет в текущем наборе
     * (scada-e17). Под тем же условием владения, что и запись: чужой проект экземпляр не трогает.
     */
    public void deleteObsolete(long projectId, Set<Long> taskIds, Set<String> variableNames) {
        String owned = " AND EXISTS (SELECT 1 FROM runtime.instance_project ip "
                + "WHERE ip.project_id = ? AND ip.instance_id = ?)";
        for (String table : List.of("automation.task_status", "automation.task_checkpoint")) {
            deleteExcept("DELETE FROM " + table + " WHERE project_id = ? AND NOT (task_id = ANY (?))" + owned,
                    projectId, "bigint", taskIds.toArray());
        }
        deleteExcept("DELETE FROM automation.variable_value WHERE project_id = ? AND NOT (name = ANY (?))" + owned,
                projectId, "text", variableNames.toArray());
    }

    private void deleteExcept(String sql, long projectId, String elementType, Object[] keep) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setLong(1, projectId);
            statement.setArray(2, connection.createArrayOf(elementType, keep));
            statement.setLong(3, projectId);
            statement.setString(4, identity.instanceId());
            return statement;
        });
    }

    public void saveStatuses(List<StatusRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO automation.task_status (project_id, task_id, name, state, last_run_at, last_duration_ms,
                                                    last_error, error_count, owner_instance, last_lag_ms, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()
                """ + OWNED + """

                ON CONFLICT (project_id, task_id) DO UPDATE
                SET name = EXCLUDED.name, state = EXCLUDED.state, last_run_at = EXCLUDED.last_run_at,
                    last_duration_ms = EXCLUDED.last_duration_ms, last_error = EXCLUDED.last_error,
                    error_count = EXCLUDED.error_count, owner_instance = EXCLUDED.owner_instance,
                    last_lag_ms = EXCLUDED.last_lag_ms, updated_at = now()""",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.projectId());
                    ps.setLong(2, row.taskId());
                    ps.setString(3, row.name());
                    ps.setString(4, row.state());
                    if (row.lastRunAtMs() == null) {
                        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        ps.setTimestamp(5, new Timestamp(row.lastRunAtMs()));
                    }
                    if (row.lastDurationMs() == null) {
                        ps.setNull(6, Types.BIGINT);
                    } else {
                        ps.setLong(6, row.lastDurationMs());
                    }
                    ps.setString(7, row.lastError());
                    ps.setLong(8, row.errorCount());
                    ps.setString(9, row.ownerInstance());
                    if (row.lastLagMs() == null) {
                        ps.setNull(10, Types.BIGINT);
                    } else {
                        ps.setLong(10, row.lastLagMs());
                    }
                    ps.setLong(11, row.projectId());
                    ps.setString(12, identity.instanceId());
                });
    }

    public List<StatusRow> statuses(long projectId) {
        return jdbc.query("""
                        SELECT task_id, name, state, last_run_at, last_duration_ms, last_error, error_count, owner_instance,
                               last_lag_ms
                        FROM automation.task_status WHERE project_id = ? ORDER BY task_id""",
                (rs, i) -> {
                    Timestamp lastRun = rs.getTimestamp(4);
                    long duration = rs.getLong(5);
                    Long durationOrNull = rs.wasNull() ? null : duration;
                    long lag = rs.getLong(9);
                    Long lagOrNull = rs.wasNull() ? null : lag;
                    return new StatusRow(projectId, rs.getLong(1), rs.getString(2), rs.getString(3),
                            lastRun == null ? null : lastRun.getTime(), durationOrNull,
                            rs.getString(6), rs.getLong(7), rs.getString(8), lagOrNull);
                }, projectId);
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize value: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null ? new HashMap<>() : mapper.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupted checkpoint JSON: " + e.getMessage(), e);
        }
    }

    private Object readValue(String json) {
        try {
            return json == null ? null : mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupted variable JSON: " + e.getMessage(), e);
        }
    }
}
