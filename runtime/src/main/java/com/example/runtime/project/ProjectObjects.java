package com.example.runtime.project;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Объекты для инспектора монитора: каждый компонент выпуска, у которого есть свойства, плоским
 * списком со сценой, на которой он стоит. Как в старой SCADA, выбор идёт из всех объектов
 * проекта, а не только открытой сцены.
 * <p>
 * Из сырого дерева, а не из DTO: так в ответ попадают ровно те поля свойства, что видит фронт
 * в схеме сцены, и добавление поля в editor не требует правки runtime.
 */
public final class ProjectObjects {

    /** Поля свойства, нужные инспектору: показать, найти значение, привести ввод. */
    private static final List<String> PROPERTY_FIELDS =
            List.of("id", "name", "label", "tag_id", "value_type", "default_value", "position");

    private ProjectObjects() {
    }

    public static List<Map<String, Object>> list(JsonNode rawTree) {
        List<Map<String, Object>> result = new ArrayList<>();
        collect(rawTree, null, result);
        // Как в выпадающем списке старой версии: по имени, без учёта регистра.
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static void collect(JsonNode component, JsonNode scene, List<Map<String, Object>> result) {
        JsonNode currentScene = "scene".equals(component.path("type").asText()) ? component : scene;
        JsonNode properties = component.path("properties");
        if (properties.isArray() && !properties.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", component.path("id").asLong());
            row.put("name", component.path("name").asText(null));
            row.put("type", component.path("type").asText(null));
            row.put("sceneId", currentScene == null ? null : currentScene.path("id").asLong());
            row.put("sceneName", currentScene == null ? null : currentScene.path("name").asText(null));
            List<Map<String, Object>> props = new ArrayList<>();
            for (JsonNode property : properties) {
                Map<String, Object> p = new LinkedHashMap<>();
                for (String field : PROPERTY_FIELDS) {
                    JsonNode value = property.get(field);
                    p.put(field, value == null || value.isNull() ? null
                            : value.isNumber() ? value.numberValue() : value.asText());
                }
                props.add(p);
            }
            // position — только для показа и nullable: такие строки в конец, как в editor.
            props.sort(Comparator.comparing(p -> (Number) p.get("position"),
                    Comparator.nullsLast(Comparator.comparingLong(Number::longValue))));
            row.put("properties", props);
            result.add(row);
        }
        for (JsonNode child : component.path("children")) {
            collect(child, currentScene, result);
        }
    }
}
