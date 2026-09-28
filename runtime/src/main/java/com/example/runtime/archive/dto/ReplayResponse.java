package com.example.runtime.archive.dto;

import java.util.List;
import java.util.Map;

/**
 * @param initial состояние всех запрошенных тегов на момент {@code from} (null у тега — значения
 *                нет); только в первой странице, в следующих — null
 * @param changes изменения по возрастанию ts
 * @param next    курсор следующей страницы; null — период выдан до конца
 */
public record ReplayResponse(Map<String, ArchiveValue> initial, List<ReplayChange> changes, String next) {
}
