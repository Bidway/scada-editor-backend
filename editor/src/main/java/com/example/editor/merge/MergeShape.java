package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Приведение снимка версии к деревьям, которые сравнивает слияние, и канонический вид для
 * сравнения.
 * <p>
 * Снимок — это сериализованный {@code ComponentResponseDto}, запрос клиента — {@code
 * ComponentCreateDto}. Слияние работает с тремя деревьями одного типа, поэтому снимки приводятся
 * к типу запроса — тем же {@code convertValue}, которым это уже делает восстановление версии.
 */
@UtilityClass
public class MergeShape {

    /**
     * Поля, которые в сравнении не участвуют.
     * <ul>
     *   <li>{@code key}, {@code parent_key} — клиентские локальные ключи, на сервере смысла
     *       не имеют;</li>
     *   <li>{@code parent_id}, {@code component_id}, {@code componentId} — принадлежность
     *       выражена положением в дереве; у новой сущности id родителя есть, а у неё самой
     *       нет, и сравнение по этому полю давало бы разницу на пустом месте;</li>
     *   <li>{@code version} — счётчик оптимистической блокировки. Он растёт от самого факта
     *       записи, а не от правки содержимого; тот же довод, по которому его вырезает
     *       {@code withoutLockCounters} при хешировании снимка.</li>
     * </ul>
     */
    private static final Set<String> IGNORED =
            Set.of("key", "parent_key", "parent_id", "component_id", "componentId", "version");

    /** Дети сцены из снимка. Пустой список, если снимок пуст — но не {@code null}. */
    public List<ComponentCreateDto> childrenOf(JsonNode content, ObjectMapper mapper) {
        JsonNode children = content == null ? null : content.get("children");
        if (children == null || children.isNull()) {
            return new ArrayList<>();
        }
        return mapper.convertValue(children, new TypeReference<List<ComponentCreateDto>>() {});
    }

    /**
     * Канонический вид: без игнорируемых полей, без {@code null}-значений и без пустых
     * коллекций.
     * <p>
     * Пустая коллекция и отсутствующая — одно и то же: снимок пишет {@code "scripts": []},
     * а DTO может дать {@code null}, и наоборот. Порядок элементов массивов сохраняется:
     * очерёдность детей на мнемосхеме значима, и слияние обязано её видеть.
     */
    public JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                JsonNode canonical = canonical(item);
                if (canonical != null) {
                    result.add(canonical);
                }
            }
            return result;
        }
        if (!node.isObject()) {
            // Целое число из JSON-текста снимка ({@code readTree}) ложится в IntNode, то же
            // число из Long-поля DTO (valueToTree) — в LongNode. Значения совпадают, но
            // IntNode.equals(LongNode) — всегда false: без нормализации любой id стал бы
            // ложным конфликтом. Дробные и так лежат в полях-строках ({@code default_value}),
            // отдельной нормализации не требуют.
            if (node.isIntegralNumber()) {
                return LongNode.valueOf(node.longValue());
            }
            return node;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        // Поля по алфавиту: порядок ключей в объекте ничего не значит, а equals у ObjectNode
        // от него не зависит — сортировка нужна только чтобы разница читалась глазами.
        for (String field : new TreeSet<>(iterable(node))) {
            if (IGNORED.contains(field)) {
                continue;
            }
            JsonNode value = canonical(node.get(field));
            if (value == null || (value.isArray() && value.isEmpty())) {
                continue;
            }
            result.set(field, value);
        }
        return result;
    }

    private List<String> iterable(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
