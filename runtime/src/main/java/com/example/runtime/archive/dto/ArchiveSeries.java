package com.example.runtime.archive.dto;

import java.util.List;

/**
 * @param initial    последняя точка до {@code from} — значение на начало периода; null — её нет
 * @param aggregated точки прорежены корзинами «минимум и максимум»
 */
public record ArchiveSeries(String tag, ArchiveValue initial, List<ArchiveValue> points, boolean aggregated) {
}
