package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** SQL чтения архива. Все выборки — по индексу (tag, ts), секции отсекаются по ts. */
@Component
public class ArchiveReader {

    static final RowMapper<ArchiveValue> VALUE = (rs, i) -> {
        boolean good = rs.getBoolean("good");
        double num = rs.getDouble("value_num");
        Object value = !good ? null : rs.wasNull() ? rs.getString("value_text") : (Object) num;
        return new ArchiveValue(rs.getTimestamp("ts").toInstant(), value, good);
    };

    private final JdbcTemplate jdbc;

    public ArchiveReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Последняя точка строго до {@code before}: обычно находится в секции тех же суток (опорная точка). */
    public ArchiveValue initial(int tag, Instant before) {
        List<ArchiveValue> rows = jdbc.query("""
                SELECT ts, value_num, value_text, good FROM runtime.tag_archive
                WHERE tag = ? AND ts < ? ORDER BY ts DESC LIMIT 1
                """, VALUE, tag, Timestamp.from(before));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** {count, строк со строковым значением} в периоде — решает, прореживать ли. */
    public long[] stats(int tag, Instant from, Instant to) {
        return jdbc.queryForObject("""
                SELECT count(*), count(value_text) FROM runtime.tag_archive
                WHERE tag = ? AND ts >= ? AND ts < ?
                """, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)},
                tag, Timestamp.from(from), Timestamp.from(to));
    }

    public List<ArchiveValue> points(int tag, Instant from, Instant to) {
        return jdbc.query("""
                SELECT ts, value_num, value_text, good FROM runtime.tag_archive
                WHERE tag = ? AND ts >= ? AND ts < ? ORDER BY ts
                """, VALUE, tag, Timestamp.from(from), Timestamp.from(to));
    }

    /**
     * Прореживание числового тега: период делится на {@code buckets} корзин, из каждой — точка
     * минимума и точка максимума (пики не сглаживаются), плюс все недостоверные точки (обрыв
     * связи на тренде должен быть виден).
     */
    public List<ArchiveValue> minMax(int tag, Instant from, Instant to, int buckets) {
        double fromEpoch = from.toEpochMilli() / 1000.0;
        double width = Math.max((to.toEpochMilli() - from.toEpochMilli()) / 1000.0 / buckets, 0.001);
        return jdbc.query("""
                SELECT ts, value_num, value_text, good FROM (
                    SELECT ts, value_num, value_text, good,
                           row_number() OVER (PARTITION BY bk ORDER BY value_num ASC NULLS LAST, ts) AS rn_min,
                           row_number() OVER (PARTITION BY bk ORDER BY value_num DESC NULLS LAST, ts) AS rn_max
                    FROM (SELECT ts, value_num, value_text, good,
                                 floor((extract(epoch FROM ts) - ?) / ?)::bigint AS bk
                          FROM runtime.tag_archive
                          WHERE tag = ? AND ts >= ? AND ts < ?) s
                ) r
                WHERE (good AND (rn_min = 1 OR rn_max = 1)) OR NOT good
                ORDER BY ts
                """, VALUE, fromEpoch, width, tag, Timestamp.from(from), Timestamp.from(to));
    }
}
