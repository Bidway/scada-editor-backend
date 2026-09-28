package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Состояние набора тегов на момент {@code before}: по одной последней точке на тег. */
    public Map<Integer, ArchiveValue> initialAll(Collection<Integer> tags, Instant before) {
        Map<Integer, ArchiveValue> out = new HashMap<>();
        jdbc.query("""
                SELECT t.id AS tag, a.ts, a.value_num, a.value_text, a.good
                FROM unnest(?::integer[]) AS t(id)
                JOIN LATERAL (SELECT ts, value_num, value_text, good FROM runtime.tag_archive
                              WHERE tag = t.id AND ts < ? ORDER BY ts DESC LIMIT 1) a ON true
                """, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("integer", tags.toArray()));
                    ps.setTimestamp(2, Timestamp.from(before));
                }, rs -> {
                    out.put(rs.getInt("tag"), VALUE.mapRow(rs, 0));
                });
        return out;
    }

    /**
     * Страница изменений набора тегов по (ts, tag); {@code afterTs}/{@code afterTag} — курсор или null.
     * Строка: {id тега, ts как Timestamp с микросекундами, ArchiveValue}.
     */
    public List<Object[]> changes(Collection<Integer> tags, Instant from, Instant to,
                                  Timestamp afterTs, Integer afterTag, int limit) {
        return jdbc.query("""
                SELECT tag, ts, value_num, value_text, good FROM runtime.tag_archive
                WHERE tag = ANY(?) AND ts >= ? AND ts < ?
                  AND (?::timestamptz IS NULL OR (ts, tag) > (?::timestamptz, ?::integer))
                ORDER BY ts, tag
                LIMIT ?
                """, ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("integer", tags.toArray()));
                    ps.setTimestamp(2, Timestamp.from(from));
                    ps.setTimestamp(3, Timestamp.from(to));
                    ps.setTimestamp(4, afterTs);
                    ps.setTimestamp(5, afterTs);
                    ps.setObject(6, afterTag, java.sql.Types.INTEGER);
                    ps.setInt(7, limit);
                }, (rs, i) -> new Object[]{rs.getInt("tag"), rs.getTimestamp("ts"), VALUE.mapRow(rs, i)});
    }
}
