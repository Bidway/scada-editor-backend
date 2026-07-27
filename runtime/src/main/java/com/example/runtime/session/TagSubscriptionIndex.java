package com.example.runtime.session;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorPropertyDto;
import com.example.runtime.client.dto.EditorScriptDto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Строится один раз при старте сессии обходом дерева проекта, полученного от editor.
 * Дальше используется на горячем пути (обработка Kafka-сообщений и ACTION), поэтому
 * все структуры — простые неизменяемые/потокобезопасные map-ы для чтения без блокировок.
 */
public class TagSubscriptionIndex {

    private final Map<String, List<Long>> tagToRawPropertyIds = new HashMap<>();
    private final Map<String, List<OnChangeBinding>> tagToOnChangeBindings = new HashMap<>();
    private final Map<Long, ScriptEntry> scriptsById = new HashMap<>();
    private final Map<Long, String> propertyNames = new HashMap<>();
    /** propertyId -> tag_id (путь узла). Нужен для обратного направления — записи тега. */
    private final Map<Long, String> propertyTagIds = new HashMap<>();
    private final Map<Long, Long> propertyComponentId = new HashMap<>();
    private final Map<Long, List<Long>> componentPropertyIds = new HashMap<>();
    private final Map<Long, Object> initialPropertyValues = new ConcurrentHashMap<>();
    private final Set<String> allTagIds = new HashSet<>();

    public static TagSubscriptionIndex build(EditorComponentDto root) {
        TagSubscriptionIndex index = new TagSubscriptionIndex();
        Deque<EditorComponentDto> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            EditorComponentDto component = queue.poll();
            if (component == null) {
                continue;
            }
            index.indexComponent(component);
            if (component.getChildren() != null) {
                queue.addAll(component.getChildren());
            }
        }
        return index;
    }

    private void indexComponent(EditorComponentDto component) {
        Long componentId = component.getId();

        if (component.getProperties() != null) {
            for (EditorPropertyDto property : component.getProperties()) {
                Long propertyId = property.getId();
                propertyNames.put(propertyId, property.getName());
                propertyComponentId.put(propertyId, componentId);
                componentPropertyIds.computeIfAbsent(componentId, id -> new ArrayList<>()).add(propertyId);
                // ConcurrentHashMap не принимает null, а default_value необязателен.
                // Отсутствие ключа = «значение не задано» — так же трактуется дальше по цепочке.
                Object defaultValue = property.getDefault_value();
                if (defaultValue != null) {
                    initialPropertyValues.put(propertyId, defaultValue);
                }

                // property_type — свободная строка без валидации ("Тег", "TAG", ...),
                // поэтому признак тега — только непустой tag_id.
                String tagId = property.getTag_id();
                if (tagId != null && !tagId.isBlank()) {
                    allTagIds.add(tagId);
                    propertyTagIds.put(propertyId, tagId);
                    tagToRawPropertyIds.computeIfAbsent(tagId, id -> new ArrayList<>()).add(propertyId);

                    String onChangeScript = property.extractOnChangeScript();
                    if (onChangeScript != null) {
                        tagToOnChangeBindings.computeIfAbsent(tagId, id -> new ArrayList<>())
                                .add(new OnChangeBinding(componentId, propertyId, onChangeScript));
                    }
                }
            }
        }

        if (component.getScripts() != null) {
            for (EditorScriptDto script : component.getScripts()) {
                scriptsById.put(script.getId(), new ScriptEntry(script.getId(), componentId, script.getName(), script.getScript()));
            }
        }
    }

    public Set<String> getAllTagIds() {
        return allTagIds;
    }

    public List<Long> rawPropertyIdsForTag(String tagId) {
        return tagToRawPropertyIds.getOrDefault(tagId, List.of());
    }

    public List<OnChangeBinding> onChangeBindingsForTag(String tagId) {
        return tagToOnChangeBindings.getOrDefault(tagId, List.of());
    }

    public ScriptEntry getScript(Long scriptId) {
        return scriptsById.get(scriptId);
    }

    public String propertyName(Long propertyId) {
        return propertyNames.get(propertyId);
    }

    public Long propertyComponentId(Long propertyId) {
        return propertyComponentId.get(propertyId);
    }

    /** Путь тега (он же Kafka-key), к которому привязано свойство; {@code null} — свойство не теговое. */
    public String tagIdOfProperty(Long propertyId) {
        return propertyTagIds.get(propertyId);
    }

    /**
     * Путь тега свойства компонента по <b>имени</b> свойства — так его называет скрипт
     * в {@code writeTag('ST', true)}. Имена уникальны в пределах компонента (их задаёт
     * автор схемы), поэтому первое совпадение и есть искомое.
     */
    public String tagIdOfComponentProperty(Long componentId, String propertyName) {
        if (propertyName == null) {
            return null;
        }
        for (Long propertyId : propertyIdsOfComponent(componentId)) {
            if (propertyName.equals(propertyNames.get(propertyId))) {
                return propertyTagIds.get(propertyId);
            }
        }
        return null;
    }

    /** Все свойства компонента (для построения `props` объекта, видимого скрипту). */
    public List<Long> propertyIdsOfComponent(Long componentId) {
        return componentPropertyIds.getOrDefault(componentId, List.of());
    }

    public Map<Long, Object> getInitialPropertyValues() {
        return initialPropertyValues;
    }
}
