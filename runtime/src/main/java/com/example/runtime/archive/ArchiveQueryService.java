package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveSeries;
import com.example.runtime.archive.dto.ArchiveValue;
import com.example.runtime.archive.dto.ArchiveValuesResponse;
import com.example.runtime.archive.dto.ReplayChange;
import com.example.runtime.archive.dto.ReplayRequest;
import com.example.runtime.archive.dto.ReplayResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Проверка запросов к архиву и сборка ответов. Неверный запрос — IllegalArgumentException (400). */
@Service
public class ArchiveQueryService {

    private static final int DEFAULT_MAX_POINTS = 1000;

    private final ArchiveReader reader;
    private final ArchiveTagDictionary dictionary;
    private final ArchiveProperties props;

    public ArchiveQueryService(ArchiveReader reader, ArchiveTagDictionary dictionary, ArchiveProperties props) {
        this.reader = reader;
        this.dictionary = dictionary;
        this.props = props;
    }

    public ArchiveValuesResponse values(List<String> tags, Instant from, Instant to, Integer maxPoints) {
        if (tags == null || tags.isEmpty()) {
            throw new IllegalArgumentException("Не указан ни один тег");
        }
        if (tags.size() > props.getMaxTagsPerRequest()) {
            throw new IllegalArgumentException("Тегов в запросе больше " + props.getMaxTagsPerRequest());
        }
        checkPeriod(from, to);
        int limit = Math.max(2, Math.min(maxPoints == null ? DEFAULT_MAX_POINTS : maxPoints, props.getMaxPoints()));

        Map<String, Integer> ids = dictionary.resolve(tags);
        List<ArchiveSeries> series = new ArrayList<>();
        for (String tag : tags) {
            Integer id = ids.get(tag);
            if (id == null) {
                series.add(new ArchiveSeries(tag, null, List.of(), false));
                continue;
            }
            long[] stats = reader.stats(id, from, to);
            boolean numeric = stats[1] == 0;
            boolean aggregate = numeric && stats[0] > limit;
            series.add(new ArchiveSeries(tag, reader.initial(id, from),
                    aggregate ? reader.minMax(id, from, to, limit / 2) : reader.points(id, from, to),
                    aggregate));
        }
        return new ArchiveValuesResponse(from, to, series);
    }

    void checkPeriod(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("Период задан неверно: нужно from < to");
        }
        if (from.isBefore(Instant.now().minus(props.getRetentionDays(), ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("Архив хранится " + props.getRetentionDays() + " дней: from раньше");
        }
    }

    /**
     * Воспроизведение: {@code initial} — на первой странице, изменения — страницами по курсору.
     * Курсор — Base64URL строки «микросекунды ts:id тега» последней строки; на паре (ts, tag)
     * страницы стыкуются без пропусков и повторов и при одинаковых ts у разных тегов.
     */
    public ReplayResponse replay(ReplayRequest request) {
        if (request.tags().size() > props.getMaxReplayTags()) {
            throw new IllegalArgumentException("Тегов для воспроизведения больше " + props.getMaxReplayTags());
        }
        checkPeriod(request.from(), request.to());
        Map<String, Integer> ids = dictionary.resolve(request.tags());
        Map<Integer, String> paths = new HashMap<>();
        ids.forEach((path, id) -> paths.put(id, path));

        Map<String, ArchiveValue> initial = null;
        if (request.after() == null) {
            Map<Integer, ArchiveValue> byId = ids.isEmpty() ? Map.of() : reader.initialAll(ids.values(), request.from());
            // LinkedHashMap допускает null — так в ответ попадает «значения нет».
            initial = new LinkedHashMap<>();
            for (String tag : request.tags()) {
                Integer id = ids.get(tag);
                initial.put(tag, id == null ? null : byId.get(id));
            }
        }

        Timestamp afterTs = null;
        Integer afterTag = null;
        if (request.after() != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(request.after()), StandardCharsets.UTF_8)
                        .split(":");
                afterTs = toTimestamp(Long.parseLong(parts[0]));
                afterTag = Integer.parseInt(parts[1]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Курсор after не разобран");
            }
        }

        int limit = props.getReplayPageSize();
        List<Object[]> rows = ids.isEmpty() ? List.of()
                : reader.changes(ids.values(), request.from(), request.to(), afterTs, afterTag, limit);
        List<ReplayChange> changes = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            ArchiveValue v = (ArchiveValue) row[2];
            changes.add(new ReplayChange(v.ts(), paths.get((Integer) row[0]), v.value(), v.good()));
        }
        String next = null;
        if (rows.size() == limit) {
            Object[] last = rows.get(rows.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (toMicros((Timestamp) last[1]) + ":" + last[0]).getBytes(StandardCharsets.UTF_8));
        }
        return new ReplayResponse(initial, changes, next);
    }

    private static long toMicros(Timestamp ts) {
        return Math.floorDiv(ts.getTime(), 1000) * 1_000_000 + ts.getNanos() / 1000;
    }

    private static Timestamp toTimestamp(long micros) {
        Timestamp ts = new Timestamp(Math.floorDiv(micros, 1_000_000) * 1000);
        ts.setNanos((int) Math.floorMod(micros, 1_000_000) * 1000);
        return ts;
    }
}
