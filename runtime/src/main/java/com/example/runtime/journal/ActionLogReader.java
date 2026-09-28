package com.example.runtime.journal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Чтение журнала действий. Любой экземпляр видит всю таблицу — база общая. */
@Component
public class ActionLogReader {

    private static final TypeReference<List<Map<String, Object>>> TAGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public ActionLogReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** По убыванию ts; фильтры projectId/username/kind необязательны. */
    public List<ActionRecord> find(Long projectId, Instant from, Instant to, String username, String kind,
                                   int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT ts, username, project_id, kind, component_id, component, target, tags::text AS tags, outcome, error
                FROM runtime.action_log WHERE ts >= ? AND ts < ?
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        if (projectId != null) {
            sql.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (username != null) {
            sql.append(" AND username = ?");
            args.add(username);
        }
        if (kind != null) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add((long) page * size);
        return jdbc.query(sql.toString(), (rs, i) -> {
            String tags = rs.getString("tags");
            try {
                return new ActionRecord(rs.getTimestamp("ts").toInstant(), rs.getString("username"),
                        (Long) rs.getObject("project_id"), rs.getString("kind"), (Long) rs.getObject("component_id"),
                        rs.getString("component"), rs.getString("target"),
                        tags == null ? null : mapper.readValue(tags, TAGS),
                        rs.getString("outcome"), rs.getString("error"));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }, args.toArray());
    }
}
