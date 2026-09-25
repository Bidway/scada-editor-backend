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

    private final String prefix;
    private final Map<String, Map<String, String>> fields;
    private final Map<String, String> legacyNames;
    private final Set<String> ambiguous;
    /** Полный путь канала → устройство из его «Имени в ПЛК» (часть до точки: LINE1V0). */
    private final Map<String, String> plcDevices;

    private ChannelIndex(String prefix, Map<String, Map<String, String>> fields, Map<String, String> legacyNames,
                         Set<String> ambiguous, Map<String, String> plcDevices) {
        this.prefix = prefix;
        this.fields = fields;
        this.legacyNames = legacyNames;
        this.ambiguous = ambiguous;
        this.plcDevices = plcDevices;
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
        Map<String, String> plcDevices = new HashMap<>();
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
            plcDevices.put(param.node(), legacy);
            String previous = legacyNames.putIfAbsent(legacy, object);
            if (previous != null && !previous.equals(object)) {
                ambiguous.add(legacy);
            }
        }
        ambiguous.forEach(legacyNames::remove);
        return new ChannelIndex(prefix, fields, legacyNames, ambiguous, plcDevices);
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

    /**
     * Указывают ли два тега на одно устройство (scada-w7gh): автопривязка не должна молча
     * подменять рабочую привязку тегом другого датчика, найденным по совпавшему старому имени.
     * <ul>
     *   <li>старый тег в этой же базе — сравниваются объекты (родители полей);</li>
     *   <li>старый тег из другой базы (переход со старой плоской) — имя устройства в его пути
     *       (сегмент перед полем: …V_ST_1.<b>LINE1V0</b>.ST) сравнивается с «Именем в ПЛК»
     *       найденного канала. Нет «Имени в ПЛК» — устройство не подтверждено, значит «другое».</li>
     * </ul>
     */
    public boolean sameDevice(String currentTag, String foundTag) {
        if (currentTag.startsWith(prefix)) {
            return parentOf(currentTag).equals(parentOf(foundTag));
        }
        String currentDevice = lastSegment(parentOf(currentTag));
        return !currentDevice.isEmpty() && currentDevice.equals(plcDevices.get(foundTag));
    }

    private static String parentOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(0, dot) : "";
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }
}
