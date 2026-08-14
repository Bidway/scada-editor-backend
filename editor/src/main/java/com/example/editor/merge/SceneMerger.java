package com.example.editor.merge;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.ComponentStateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Трёхстороннее слияние дерева сцены.
 * <p>
 * Чистая функция: ни Spring, ни JPA, ни версий — три дерева на вход, результат на выход. Это не
 * эстетика, а стоимость проверки: в этом модуле каждый интеграционный тест поднимает Postgres, а
 * случаев здесь десятки.
 * <p>
 * Сопоставление — сначала по {@code id}, при его отсутствии по имени. Сравнение только по именам
 * давало бы ложный конфликт на каждом переименовании: «Насос» → «Насос-1» выглядит как удаление
 * плюс создание.
 */
public class SceneMerger {

    private static final RowSpec<PropertyCreateDto> PROPERTIES = new RowSpec<>(
            "component_property", PropertyCreateDto::getId, PropertyCreateDto::getName);
    private static final RowSpec<ScriptCreateDto> SCRIPTS = new RowSpec<>(
            "script", ScriptCreateDto::getId, ScriptCreateDto::getName);
    private static final RowSpec<ComponentStateDto> STATES = new RowSpec<>(
            "component_state", ComponentStateDto::getId, ComponentStateDto::getName);
    private static final RowSpec<EventPayloadDto> EVENTS = new RowSpec<>(
            "component_event", EventPayloadDto::getId, EventPayloadDto::getEvent_type);
    private static final RowSpec<BindingPayloadDto> BINDINGS = new RowSpec<>(
            "binding", BindingPayloadDto::getId, BindingPayloadDto::getName);

    private final ObjectMapper mapper;

    public SceneMerger(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SceneMerge merge(List<ComponentCreateDto> base, List<ComponentCreateDto> mine,
                            List<ComponentCreateDto> theirs) {
        List<MergeConflict> conflicts = new ArrayList<>();
        List<MergeChange> changes = new ArrayList<>();
        List<ComponentCreateDto> merged =
                mergeComponents(nullToEmpty(base), nullToEmpty(mine), nullToEmpty(theirs),
                        "", conflicts, changes);
        return new SceneMerge(merged, conflicts, changes);
    }

    /**
     * Слияние списка строк одного вида. Ключ — id, при его отсутствии имя; порядок результата
     * берётся от моей стороны, чужие добавления дописываются в конец.
     */
    private <T> List<T> mergeRows(List<T> base, List<T> mine, List<T> theirs, RowSpec<T> spec,
                                  String path, List<MergeConflict> conflicts,
                                  List<MergeChange> changes) {
        Map<String, T> baseByKey = byKey(base, spec);
        Map<String, T> mineByKey = byKey(mine, spec);
        Map<String, T> theirsByKey = byKey(theirs, spec);

        List<T> result = new ArrayList<>();
        for (String key : allKeys(baseByKey, mineByKey, theirsByKey)) {
            T b = baseByKey.get(key);
            T m = mineByKey.get(key);
            T t = theirsByKey.get(key);
            String rowPath = path.isEmpty() ? label(key, spec, m, t, b)
                    : path + " / " + label(key, spec, m, t, b);

            Resolution<T> resolution = resolve(b, m, t, spec.entity(), rowPath, conflicts, changes);
            if (resolution.value() != null) {
                result.add(resolution.value());
            }
        }
        return result;
    }

    /**
     * Общая таблица исходов — одна на строки и на компоненты.
     * <p>
     * Сравнение идёт по каноническому виду сущности <b>без вложенных коллекций</b>: иначе правка
     * одного скрипта делала бы «изменённым» весь компонент и конфликтовала бы с любой чужой
     * правкой в нём — вложенные коллекции сливаются отдельно, своим кодом, и своя же правка там
     * даёт свою запись в {@code changes}/{@code conflicts}.
     * <p>
     * Исключение — развилки «удалили ли меня»/«удалили ли их» ниже: для них берём
     * {@code sameFull}, полное сравнение с вложенными коллекциями. Иначе правка скрипта внутри
     * компонента, чьи собственные скалярные поля не менялись, не признавалась бы правкой вовсе —
     * и удаление компонента другой стороной проходило бы тихо, без конфликта, теряя эту правку.
     */
    private <T> Resolution<T> resolve(T b, T m, T t, String entity, String path,
                                      List<MergeConflict> conflicts, List<MergeChange> changes) {
        boolean changedByMe = !same(b, m);
        boolean changedByThem = !same(b, t);

        if (m == null && t == null) {
            return new Resolution<>(null, false);
        }
        // b == null здесь означает не «строку удалили», а «строки в базе не было вовсе» —
        // сторона с содержимым её просто завела, а не досталась в наследство от базы.
        // Без этой развилки любое чужое добавление (b и m оба null, t — новая строка) тоже
        // попадало бы в ветку changedByThem и объявлялось конфликтом DELETED_BY_YOU: b == null
        // делает changedByThem/changedByMe тривиально true при любом содержимом.
        if (m == null) {
            if (b == null) {
                changes.add(new MergeChange(entity, path, ChangeKind.ADDED));
                return new Resolution<>(t, true);
            }
            if (!sameFull(b, t)) {
                conflicts.add(new MergeConflict(ConflictKind.DELETED_BY_YOU, entity, path,
                        text(b), null, text(t)));
                return new Resolution<>(null, false);
            }
            return new Resolution<>(null, false);
        }
        if (t == null) {
            if (b == null) {
                // Строка есть только у меня и в базе её не было — моё же локальное добавление,
                // а не то, что «подмешалось с чужой стороны» (см. javadoc {@link MergeChange}),
                // поэтому в changes оно не попадает.
                return new Resolution<>(m, true);
            }
            if (!sameFull(b, m)) {
                conflicts.add(new MergeConflict(ConflictKind.DELETED_BY_THEM, entity, path,
                        text(b), text(m), null));
                return new Resolution<>(null, false);
            }
            changes.add(new MergeChange(entity, path, ChangeKind.DELETED));
            return new Resolution<>(null, false);
        }
        if (changedByMe && changedByThem && !same(m, t)) {
            conflicts.add(new MergeConflict(
                    b == null ? ConflictKind.BOTH_ADDED : ConflictKind.BOTH_MODIFIED,
                    entity, path, text(b), text(m), text(t)));
            return new Resolution<>(m, true);
        }
        if (changedByThem && !changedByMe) {
            changes.add(new MergeChange(entity, path,
                    b == null ? ChangeKind.ADDED : ChangeKind.MODIFIED));
            return new Resolution<>(t, true);
        }
        return new Resolution<>(m, true);
    }

    /** Значение, которое попадает в слитое дерево, и признак «сущность жива». */
    private record Resolution<T>(T value, boolean present) {
    }

    private <T> Map<String, T> byKey(List<T> rows, RowSpec<T> spec) {
        Map<String, T> byKey = new java.util.LinkedHashMap<>();
        for (T row : nullToEmpty(rows)) {
            byKey.put(keyOf(row, spec), row);
        }
        return byKey;
    }

    /**
     * Ключ сопоставления. Строка с id адресуется им; без id — именем с префиксом, чтобы
     * «id=7» и «имя 7» не столкнулись в одной карте.
     */
    private <T> String keyOf(T row, RowSpec<T> spec) {
        Long id = spec.idOf().apply(row);
        if (id != null) {
            return "id:" + id;
        }
        String key = spec.keyOf().apply(row);
        return "name:" + (key == null ? "" : key.trim());
    }

    private <T> String label(String key, RowSpec<T> spec, T m, T t, T b) {
        T any = m != null ? m : (t != null ? t : b);
        String name = any == null ? null : spec.keyOf().apply(any);
        return name == null ? key : name;
    }

    private Set<String> allKeys(Map<String, ?> base, Map<String, ?> mine, Map<String, ?> theirs) {
        Set<String> keys = new LinkedHashSet<>(mine.keySet());
        keys.addAll(theirs.keySet());
        keys.addAll(base.keySet());
        return keys;
    }

    /** Равенство по каноническому виду без вложенных коллекций. */
    private boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(scalars(a), scalars(b));
    }

    /** Равенство по полному каноническому виду, вложенные коллекции включая. */
    private boolean sameFull(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(MergeShape.canonical(mapper.valueToTree(a)),
                MergeShape.canonical(mapper.valueToTree(b)));
    }

    private JsonNode scalars(Object value) {
        JsonNode node = MergeShape.canonical(mapper.valueToTree(value));
        if (node != null && node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = node.deepCopy();
            copy.remove(List.of("children", "properties", "scripts", "states", "events", "bindings"));
            return copy;
        }
        return node;
    }

    private String text(Object value) {
        JsonNode node = scalars(value);
        return node == null ? null : node.toString();
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /**
     * Слияние списка компонентов одного уровня дерева. Рекурсивна: в конце сама вызывает себя для
     * {@code children} каждого выжившего компонента, поэтому обходит дерево на любую глубину.
     */
    private List<ComponentCreateDto> mergeComponents(
            List<ComponentCreateDto> base, List<ComponentCreateDto> mine,
            List<ComponentCreateDto> theirs, String path,
            List<MergeConflict> conflicts, List<MergeChange> changes) {
        RowSpec<ComponentCreateDto> spec =
                new RowSpec<>("component", ComponentCreateDto::getId, ComponentCreateDto::getName);
        Map<String, ComponentCreateDto> baseByKey = byKey(base, spec);
        Map<String, ComponentCreateDto> mineByKey = byKey(mine, spec);
        Map<String, ComponentCreateDto> theirsByKey = byKey(theirs, spec);

        List<ComponentCreateDto> result = new ArrayList<>();
        for (String key : allKeys(baseByKey, mineByKey, theirsByKey)) {
            ComponentCreateDto b = baseByKey.get(key);
            ComponentCreateDto m = mineByKey.get(key);
            ComponentCreateDto t = theirsByKey.get(key);
            String componentPath = path.isEmpty() ? label(key, spec, m, t, b)
                    : path + " / " + label(key, spec, m, t, b);

            Resolution<ComponentCreateDto> resolution =
                    resolve(b, m, t, "component", componentPath, conflicts, changes);
            if (!resolution.present()) {
                continue;
            }
            ComponentCreateDto merged = resolution.value();
            merged.setProperties(mergeRows(rows(b, ComponentCreateDto::getProperties),
                    rows(m, ComponentCreateDto::getProperties),
                    rows(t, ComponentCreateDto::getProperties),
                    PROPERTIES, componentPath, conflicts, changes));
            merged.setScripts(mergeRows(rows(b, ComponentCreateDto::getScripts),
                    rows(m, ComponentCreateDto::getScripts),
                    rows(t, ComponentCreateDto::getScripts),
                    SCRIPTS, componentPath, conflicts, changes));
            merged.setStates(mergeRows(rows(b, ComponentCreateDto::getStates),
                    rows(m, ComponentCreateDto::getStates),
                    rows(t, ComponentCreateDto::getStates),
                    STATES, componentPath, conflicts, changes));
            merged.setEvents(mergeRows(rows(b, ComponentCreateDto::getEvents),
                    rows(m, ComponentCreateDto::getEvents),
                    rows(t, ComponentCreateDto::getEvents),
                    EVENTS, componentPath, conflicts, changes));
            merged.setBindings(mergeRows(rows(b, ComponentCreateDto::getBindings),
                    rows(m, ComponentCreateDto::getBindings),
                    rows(t, ComponentCreateDto::getBindings),
                    BINDINGS, componentPath, conflicts, changes));
            checkChildrenOrder(b, m, t, componentPath, conflicts);
            merged.setChildren(mergeComponents(
                    rows(b, ComponentCreateDto::getChildren),
                    rows(m, ComponentCreateDto::getChildren),
                    rows(t, ComponentCreateDto::getChildren),
                    componentPath, conflicts, changes));
            result.add(merged);
        }
        return result;
    }

    private <T> List<T> rows(ComponentCreateDto component, Function<ComponentCreateDto, List<T>> of) {
        return component == null ? List.of() : nullToEmpty(of.apply(component));
    }

    /**
     * Одновременная перестановка детей с двух сторон. Разрешить её автоматически нельзя: обе
     * очерёдности осмысленны, а выбрать за человека — значит молча испортить порядок отрисовки.
     * Перестановка одной стороной конфликтом не считается — там спорить не с кем.
     */
    private void checkChildrenOrder(ComponentCreateDto b, ComponentCreateDto m,
                                    ComponentCreateDto t, String path,
                                    List<MergeConflict> conflicts) {
        if (b == null || m == null || t == null) {
            return;
        }
        List<String> baseOrder = order(b);
        List<String> myOrder = order(m);
        List<String> theirOrder = order(t);
        // Сравниваем только общую часть: добавления и удаления разбирает основной алгоритм,
        // и они сами по себе порядок не ломают.
        List<String> common = new ArrayList<>(baseOrder);
        common.retainAll(myOrder);
        common.retainAll(theirOrder);
        List<String> mineCommon = new ArrayList<>(myOrder);
        mineCommon.retainAll(common);
        List<String> theirsCommon = new ArrayList<>(theirOrder);
        theirsCommon.retainAll(common);
        List<String> baseCommon = new ArrayList<>(baseOrder);
        baseCommon.retainAll(common);

        if (!mineCommon.equals(baseCommon) && !theirsCommon.equals(baseCommon)
                && !mineCommon.equals(theirsCommon)) {
            conflicts.add(new MergeConflict(ConflictKind.BOTH_MODIFIED, "children_order", path,
                    String.join(", ", baseCommon), String.join(", ", mineCommon),
                    String.join(", ", theirsCommon)));
        }
    }

    private List<String> order(ComponentCreateDto component) {
        RowSpec<ComponentCreateDto> spec =
                new RowSpec<>("component", ComponentCreateDto::getId, ComponentCreateDto::getName);
        return rows(component, ComponentCreateDto::getChildren).stream()
                .map(child -> keyOf(child, spec))
                .collect(Collectors.toList());
    }
}
