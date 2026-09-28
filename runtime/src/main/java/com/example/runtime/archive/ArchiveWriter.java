package com.example.runtime.archive;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Единственный поток, который ходит в БД за архив и журнал. Раз в {@code flush-interval-ms}
 * сливает очереди пачками по {@code batch-size}.
 * <p>
 * Ошибка БД: пачка остаётся у писателя и повторяется с паузой, удваивающейся до 30 с; очередь
 * тем временем копится и при переполнении сбрасывает точки (счётчик {@code dropped}). Приём
 * телеметрии от этого не зависит никогда.
 */
@Component
@Slf4j
public class ArchiveWriter {

    /** Дополнительный получатель такта записи — журнал действий. */
    public interface FlushSink {
        void flushOnce() throws Exception;
    }

    private static final long MAX_BACKOFF_MS = 30_000;
    private static final String INSERT =
            "INSERT INTO runtime.tag_archive(tag, ts, value_num, value_text, good) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final ArchiveRecorder recorder;
    private final ArchiveTagDictionary dictionary;
    private final ArchiveProperties props;
    private final List<FlushSink> sinks = new CopyOnWriteArrayList<>();

    /** Пачка, не записанная из-за ошибки, — повторяется раньше новых точек. */
    private List<ArchivePoint> pending = List.of();
    private long backoffMs = 0;
    private long nextAttemptAt = 0;
    private volatile Instant lastFlushAt;
    private volatile String lastError;

    public ArchiveWriter(JdbcTemplate jdbc, ArchiveRecorder recorder, ArchiveTagDictionary dictionary,
                         ArchiveProperties props) {
        this.jdbc = jdbc;
        this.recorder = recorder;
        this.dictionary = dictionary;
        this.props = props;
    }

    public void addSink(FlushSink sink) {
        sinks.add(sink);
    }

    @Scheduled(fixedDelayString = "${runtime.archive.flush-interval-ms:1000}")
    public synchronized void flush() {
        if (System.currentTimeMillis() < nextAttemptAt) {
            return;
        }
        try {
            if (pending.isEmpty()) {
                pending = recorder.queue().drain(props.getBatchSize());
            }
            while (!pending.isEmpty()) {
                write(pending);
                pending = recorder.queue().drain(props.getBatchSize());
            }
            for (FlushSink sink : sinks) {
                sink.flushOnce();
            }
            lastFlushAt = Instant.now();
            lastError = null;
            backoffMs = 0;
        } catch (Exception e) {
            backoffMs = backoffMs == 0 ? props.getFlushIntervalMs() : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            lastError = e.getMessage();
            log.warn("Archive write failed, retry in {} ms ({} points pending, queue {}): {}",
                    backoffMs, pending.size(), recorder.queue().size(), e.getMessage());
        }
    }

    /** Для тестов: снять паузу повтора. */
    synchronized void retryNow() {
        nextAttemptAt = 0;
    }

    private void write(List<ArchivePoint> batch) {
        Map<String, Integer> ids = dictionary.ensure(batch.stream().map(ArchivePoint::tag).toList());
        jdbc.batchUpdate(INSERT, batch, batch.size(), (ps, p) -> {
            ps.setInt(1, ids.get(p.tag()));
            ps.setTimestamp(2, new Timestamp(p.ts()));
            if (p.num() != null) {
                ps.setDouble(3, p.num());
            } else {
                ps.setNull(3, Types.DOUBLE);
            }
            ps.setString(4, p.text());
            ps.setBoolean(5, p.good());
        });
    }

    public Instant lastFlushAt() {
        return lastFlushAt;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized int pendingSize() {
        return pending.size();
    }
}
