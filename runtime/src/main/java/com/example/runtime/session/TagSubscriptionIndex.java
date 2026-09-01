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

    private final Map<String, List<OnChangeBinding>> tagToOnChangeBindings = new HashMap<>();
    private final Map<Long, ScriptEntry> scriptsById = new HashMap<>();
    private final Map<Long, String> propertyNames = new HashMap<>();
    /** propertyId -> tag_id (путь узла). Нужен для обратного направления — записи тега. */
    private final Map<Long, String> propertyTagIds = new HashMap<>();
    private final Map<Long, Long> propertyComponentId = new HashMap<>();
    private final Map<Long, List<Long>> componentPropertyIds = new HashMap<>();
    private final Map<Long, Object> initialPropertyValues = new ConcurrentHashMap<>();
    private final Set<String> allTagIds = new HashSet<>();

    /**
     * Общий префикс путей всех тегов проекта (для {@code writeProjectTag} из скрипта),
     * посчитан один раз при построении — см. {@link #computeProjectTagPrefix}.
     */
    private String projectTagPrefix = "";

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
        index.projectTagPrefix = computeProjectTagPrefix(index.allTagIds);
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
        Long propertyId = propertyIdOfComponentProperty(componentId, propertyName);
        return propertyId == null ? null : propertyTagIds.get(propertyId);
    }

    /**
     * Свойство компонента по имени. Значения наборов адресуют строку именно так — по имени, а не
     * по id, пришедшему из editor: набор переживает и перепривязку строки на другой тег, и её
     * пересоздание. (До 07.08.2026 у этого был ещё более жёсткий повод: пересохранение таблицы
     * в editor пересоздавало id всех строк. Теперь строки сопоставляются по имени и id стабильны,
     * но адресация набора от этого не зависит и остаётся прежней.)
     */
    public Long propertyIdOfComponentProperty(Long componentId, String propertyName) {
        if (propertyName == null) {
            return null;
        }
        String name = propertyName.trim();
        for (Long propertyId : propertyIdsOfComponent(componentId)) {
            if (name.equals(propertyNames.get(propertyId))) {
                return propertyId;
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

    /**
     * Достраивает короткий путь тега (без общего префикса проекта, например
     * {@code FQT_ST.LINE1FQT1.ST}) до полного ({@code Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST})
     * для {@code writeProjectTag} из скрипта. Полный путь, уже начинающийся с этого префикса,
     * возвращается как есть — без двойного дописывания. Если у проекта ещё нет ни одного
     * известного тега (свежий проект без теговых свойств), префикс пуст и путь возвращается
     * без изменений — тогда единственный рабочий вариант это {@code writeTagPath} с полным путём.
     */
    public String resolveTagPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (projectTagPrefix.isEmpty() || trimmed.startsWith(projectTagPrefix + ".")) {
            return trimmed;
        }
        return projectTagPrefix + "." + trimmed;
    }

    /**
     * Общий префикс проекта — наибольший общий по сегментам (через точку) префикс среди
     * всех известных {@code tag_id}. Все теги одного проекта на практике идут с одного узла
     * ПЛК (сайт+узел), поэтому это работает без похода в {@code channel} — вопреки соблазну,
     * туда обращаться нельзя (см. CLAUDE.md).
     * <p>
     * Префикс намеренно никогда не занимает больше, чем (минимум сегментов среди тегов − 1):
     * без этой защиты бедное разнообразие тегов в проекте (один-единственный тег, или
     * несколько свойств, привязанных к одному и тому же тегу) дало бы префикс, совпадающий
     * с целым тегом, — и обратная операция ({@code writeProjectTag} с пустым остатком или с
     * последним сегментом) молча ломалась бы задвоением всего пути.
     */
    private static String computeProjectTagPrefix(Set<String> tagIds) {
        if (tagIds.isEmpty()) {
            return "";
        }
        String[] common = null;
        int minSegments = Integer.MAX_VALUE;
        for (String tagId : tagIds) {
            String[] segments = tagId.split("\\.");
            minSegments = Math.min(minSegments, segments.length);
            common = common == null ? segments : commonLeadingSegments(common, segments);
        }
        int cap = Math.max(0, minSegments - 1);
        int length = Math.min(common.length, cap);
        return length == 0 ? "" : String.join(".", java.util.Arrays.copyOf(common, length));
    }

    private static String[] commonLeadingSegments(String[] a, String[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i].equals(b[i])) {
            i++;
        }
        return java.util.Arrays.copyOf(a, i);
    }
}
