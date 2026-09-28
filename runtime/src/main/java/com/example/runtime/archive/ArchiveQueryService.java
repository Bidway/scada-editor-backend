package com.example.runtime.archive;

import com.example.runtime.archive.dto.ArchiveSeries;
import com.example.runtime.archive.dto.ArchiveValuesResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
}
