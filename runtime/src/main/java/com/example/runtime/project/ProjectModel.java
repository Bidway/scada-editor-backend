package com.example.runtime.project;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.session.TagSubscriptionIndex;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Дерево проекта одного выпуска. Неизменяемое и заменяется целиком: индекс, дерево для скриптов и
 * сырой JSON для монитора обязаны быть из одного выпуска.
 * <p>
 * {@code rawTree} нужен отдельно: {@link EditorComponentDto} — неполное зеркало editor (нет
 * координат, стилей, meta), сцену монитору отдаём из JSON, иначе фронт потеряет поля.
 */
public record ProjectModel(int versionNo, JsonNode rawTree, EditorComponentDto tree, TagSubscriptionIndex index) {
}
