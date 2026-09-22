package com.example.editor.service.autobind;

import com.example.editor.client.ChannelTree;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Какой объект базы каналов стоит за именем компонента и какой путь у его поля. Канал — узел без
 * детей: поле — его последний сегмент, объект — путь родителя относительно корня базы. Объект
 * ищется по объектному пути (LINE1.V0) и по старому имени — части «Имени в ПЛК» до точки (LINE1V0).
 */
public final class ChannelIndex {

    public static final String PLC_NAME = "Имя в ПЛК";

    private final Map<String, Map<String, String>> fields;
    private final Map<String, String> legacyNames;
    private final Set<String> ambiguous;

    private ChannelIndex(Map<String, Map<String, String>> fields, Map<String, String> legacyNames,
                         Set<String> ambiguous) {
        this.fields = fields;
        this.legacyNames = legacyNames;
        this.ambiguous = ambiguous;
    }

    public static ChannelIndex build(String root, ChannelTree tree) {
        String prefix = root + ".";
        Set<String> parents = new HashSet<>();
        for (String node : tree.nodes()) {
            int dot = node.lastIndexOf('.');
            if (dot > 0) {
                parents.add(node.substring(0, dot));
            }
        }

        Map<String, Map<String, String>> fields = new HashMap<>();
        for (String node : tree.nodes()) {
            if (!node.startsWith(prefix) || parents.contains(node)) {
                continue;
            }
            String relative = node.substring(prefix.length());
            int dot = relative.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            fields.computeIfAbsent(relative.substring(0, dot), key -> new HashMap<>())
                    .put(relative.substring(dot + 1), node);
        }

        Map<String, String> legacyNames = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (ChannelTree.Param param : tree.params()) {
            if (!PLC_NAME.equals(param.name()) || param.value() == null || param.node() == null
                    || !param.node().startsWith(prefix)) {
                continue;
            }
            int valueDot = param.value().indexOf('.');
            String relative = param.node().substring(prefix.length());
            int dot = relative.lastIndexOf('.');
            if (valueDot <= 0 || dot <= 0) {
                continue;
            }
            String legacy = param.value().substring(0, valueDot);
            String object = relative.substring(0, dot);
            String previous = legacyNames.putIfAbsent(legacy, object);
            if (previous != null && !previous.equals(object)) {
                ambiguous.add(legacy);
            }
        }
        ambiguous.forEach(legacyNames::remove);
        return new ChannelIndex(fields, legacyNames, ambiguous);
    }

    public Optional<String> objectOf(String componentName) {
        if (componentName == null) {
            return Optional.empty();
        }
        String name = componentName.trim();
        if (fields.containsKey(name)) {
            return Optional.of(name);
        }
        if (ambiguous.contains(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(legacyNames.get(name));
    }

    public Optional<String> tagOf(String object, String field) {
        return Optional.ofNullable(fields.getOrDefault(object, Map.of()).get(field));
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }
}
