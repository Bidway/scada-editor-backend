package com.example.automation.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Рабочее состояние сервиса в схеме {@code automation}. Записи контрольных точек и переменных
 * идут с условием {@code epoch <= :myEpoch}: экземпляр, потерявший партицию («зомби»), свежее
 * состояние нового владельца не перетрёт.
 */
@Component
@RequiredArgsConstructor
public class AutomationStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record CheckpointRow(long projectId, long taskId, long epoch, String definitionHash,
                                Map<String, Object> state) {
    }

    public record VariableRow(long projectId, String name, Object value, long epoch) {
    }

    public record StatusRow(long projectId, long taskId, String name, String state, Long lastRunAtMs,
                            Long lastDurationMs, String lastError, long errorCount, String ownerInstance) {
    }

    /** Новое поколение владения партицией; монотонно растёт, пока жива строка. */
    public long bumpEpoch(int partition, String owner) {
        Long epoch = jdbc.queryForObject("""
                INSERT INTO automation.partition_epoch (partition, epoch, owner, acquired_at)
                VALUES (?, 1, ?, now())
                ON CONFLICT (partition) DO UPDATE
                SET epoch = automation.partition_epoch.epoch + 1, owner = EXCLUDED.owner, acquired_at = now()
                RETURNING epoch""", Long.class, partition, owner);
        if (epoch == null) {
            throw new IllegalStateException("No epoch returned for partition " + partition);
        }
        return epoch;
    }

    public Optional<CheckpointRow> loadCheckpoint(long projectId, long taskId) {
        return jdbc.query("""
                        SELECT epoch, definition_hash, state::text FROM automation.task_checkpoint
                        WHERE project_id = ? AND task_id = ?""",
                (rs, i) -> new CheckpointRow(projectId, taskId, rs.getLong(1), rs.getString(2), readMap(rs.getString(3))),
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
                INSERT INTO automation.task_checkpoint (project_id, task_id, epoch, definition_hash, state, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                ON CONFLICT (project_id, task_id) DO UPDATE
                SET epoch = EXCLUDED.epoch, definition_hash = EXCLUDED.definition_hash,
                    state = EXCLUDED.state, updated_at = now()
                WHERE automation.task_checkpoint.epoch <= EXCLUDED.epoch""",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.projectId());
                    ps.setLong(2, row.taskId());
                    ps.setLong(3, row.epoch());
                    ps.setString(4, row.definitionHash());
                    ps.setString(5, write(row.state()));
                });
    }

    public void saveVariables(List<VariableRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO automation.variable_value (project_id, name, value, quality, epoch, updated_at)
                VALUES (?, ?, ?::jsonb, 'GOOD', ?, now())
                ON CONFLICT (project_id, name) DO UPDATE
                SET value = EXCLUDED.value, epoch = EXCLUDED.epoch, updated_at = now()
                WHERE automation.variable_value.epoch <= EXCLUDED.epoch""",
                rows, rows.size(), (ps, row) -> {
                    ps.setLong(1, row.projectId());
                    ps.setString(2, row.name());
                    ps.setString(3, write(row.value()));
                    ps.setLong(4, row.epoch());
                });
    }

    public void saveStatuses(List<StatusRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO automation.task_status (project_id, task_id, name, state, last_run_at, last_duration_ms,
                                                    last_error, error_count, owner_instance, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (project_id, task_id) DO UPDATE
                SET name = EXCLUDED.name, state = EXCLUDED.state, last_run_at = EXCLUDED.last_run_at,
                    last_duration_ms = EXCLUDED.last_duration_ms, last_error = EXCLUDED.last_error,
                    error_count = EXCLUDED.error_count, owner_instance = EXCLUDED.owner_instance, updated_at = now()""",
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
                });
    }

    public List<StatusRow> statuses(long projectId) {
        return jdbc.query("""
                        SELECT task_id, name, state, last_run_at, last_duration_ms, last_error, error_count, owner_instance
                        FROM automation.task_status WHERE project_id = ? ORDER BY task_id""",
                (rs, i) -> {
                    Timestamp lastRun = rs.getTimestamp(4);
                    long duration = rs.getLong(5);
                    Long durationOrNull = rs.wasNull() ? null : duration;
                    return new StatusRow(projectId, rs.getLong(1), rs.getString(2), rs.getString(3),
                            lastRun == null ? null : lastRun.getTime(), durationOrNull,
                            rs.getString(6), rs.getLong(7), rs.getString(8));
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
