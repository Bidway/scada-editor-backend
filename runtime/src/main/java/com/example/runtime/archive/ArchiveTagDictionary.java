package com.example.runtime.archive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Путь тега ↔ внутренний id строки архива. Id — только для компактности хранения (путь с
 * кириллицей ~60 байт на каждую из миллиона строк в сутки); наружу, в API, идёт только путь.
 */
@Component
public class ArchiveTagDictionary {

    private final JdbcTemplate jdbc;
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    public ArchiveTagDictionary(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Id всех путей; недостающие создаются. */
    public Map<String, Integer> ensure(Collection<String> paths) {
        List<String> missing = missing(paths);
        if (!missing.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO runtime.archive_tag(path) VALUES (?) ON CONFLICT (path) DO NOTHING",
                    missing, missing.size(), (ps, path) -> ps.setString(1, path));
            load(missing);
        }
        return pick(paths);
    }

    /** Id только существующих путей — для чтения; неизвестный путь в ответ не попадает. */
    public Map<String, Integer> resolve(Collection<String> paths) {
        List<String> missing = missing(paths);
        if (!missing.isEmpty()) {
            load(missing);
        }
        return pick(paths);
    }

    private List<String> missing(Collection<String> paths) {
        return paths.stream().filter(p -> !cache.containsKey(p)).distinct().toList();
    }

    private void load(List<String> paths) {
        jdbc.query("SELECT id, path FROM runtime.archive_tag WHERE path = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", paths.toArray())),
                rs -> {
                    cache.put(rs.getString("path"), rs.getInt("id"));
                });
    }

    private Map<String, Integer> pick(Collection<String> paths) {
        Map<String, Integer> out = new HashMap<>();
        for (String p : paths) {
            Integer id = cache.get(p);
            if (id != null) {
                out.put(p, id);
            }
        }
        return out;
    }
}
