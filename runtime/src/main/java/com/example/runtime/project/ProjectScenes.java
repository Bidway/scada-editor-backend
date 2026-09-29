package com.example.runtime.project;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сцены — прямые дети проекта с {@code type = scene}, как у editor
 * ({@code findByParentIdAndType(projectId, "scene")}). Формы ответов — те же, что у
 * {@code GET /api/editor/components/scenes} и {@code GET /api/editor/components/{id}}.
 */
public final class ProjectScenes {

    private ProjectScenes() {
    }

    public static List<Map<String, Object>> list(JsonNode rawTree) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode child : rawTree.path("children")) {
            if ("scene".equals(child.path("type").asText())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", child.path("id").asLong());
                row.put("name", child.path("name").asText(null));
                row.put("project_id", rawTree.path("id").asLong());
                result.add(row);
            }
        }
        return result;
    }

    public static Optional<JsonNode> find(JsonNode rawTree, long sceneId) {
        for (JsonNode child : rawTree.path("children")) {
            if ("scene".equals(child.path("type").asText()) && child.path("id").asLong() == sceneId) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }
}
