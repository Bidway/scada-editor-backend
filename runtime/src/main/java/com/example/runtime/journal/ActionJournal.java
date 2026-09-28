package com.example.runtime.journal;

import com.example.runtime.archive.ArchiveProperties;
import com.example.runtime.archive.ArchiveWriter;
import com.example.runtime.archive.BoundedQueue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.function.Supplier;

/**
 * Журнал действий оператора. Пишется асинхронно тем же писателем, что и архив; но потеря строки —
 * потеря факта «кто нажал», поэтому при полной очереди строка пишется синхронно на потоке
 * действия (действий на порядки меньше, чем значений тегов). Ошибка записи — в лог, действие
 * выполняется в любом случае.
 */
@Component
@Slf4j
public class ActionJournal {

    private static final String INSERT = """
            INSERT INTO runtime.action_log(ts, username, project_id, kind, component_id, component, target, tags, outcome, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArchiveProperties props;
    private final BoundedQueue<ActionRecord> queue;

    @Autowired
    public ActionJournal(JdbcTemplate jdbc, ObjectMapper mapper, ArchiveProperties props, ArchiveWriter writer) {
        this(jdbc, mapper, props);
        writer.addSink(this::flushOnce);
    }

    /** Для тестов: без писателя — {@link #flushOnce()} зовётся руками. */
    public ActionJournal(JdbcTemplate jdbc, ObjectMapper mapper, ArchiveProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.queue = new BoundedQueue<>(props.getQueueCapacity());
    }

    public void record(ActionRecord r) {
        if (!props.isEnabled()) {
            return;
        }
        if (queue.offer(r)) {
            return;
        }
        try {
            write(List.of(r));
        } catch (Exception e) {
            log.warn("Action log write failed ({} {} by {}): {}", r.kind(), r.target(), r.username(), e.getMessage());
        }
    }

    /** Выполняет действие и пишет его исход; исключение действия пробрасывается дальше. */
    public <T> T around(ActionRecord base, Supplier<T> call) {
        try {
            T result = call.get();
            record(base.ok());
            return result;
        } catch (RuntimeException e) {
            record(base.failed(e.getMessage()));
            throw e;
        }
    }

    public void flushOnce() {
        List<ActionRecord> batch = queue.drain(props.getBatchSize());
        if (!batch.isEmpty()) {
            write(batch);
        }
    }

    private void write(List<ActionRecord> batch) {
        jdbc.batchUpdate(INSERT, batch, batch.size(), (ps, r) -> {
            ps.setTimestamp(1, Timestamp.from(r.ts()));
            ps.setString(2, r.username());
            ps.setObject(3, r.projectId(), Types.BIGINT);
            ps.setString(4, r.kind());
            ps.setObject(5, r.componentId(), Types.BIGINT);
            ps.setString(6, r.component());
            ps.setString(7, r.target());
            ps.setString(8, toJson(r.tags()));
            ps.setString(9, r.outcome());
            ps.setString(10, r.error());
        });
    }

    private String toJson(Object tags) {
        if (tags == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
