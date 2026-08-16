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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
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
            "component_property", PropertyCreateDto::getId, PropertyCreateDto::getName,
            PropertyCreateDto::setId);
    private static final RowSpec<ScriptCreateDto> SCRIPTS = new RowSpec<>(
            "script", ScriptCreateDto::getId, ScriptCreateDto::getName, ScriptCreateDto::setId);
    private static final RowSpec<ComponentStateDto> STATES = new RowSpec<>(
            "component_state", ComponentStateDto::getId, ComponentStateDto::getName,
            ComponentStateDto::setId);
    private static final RowSpec<EventPayloadDto> EVENTS = new RowSpec<>(
            "component_event", EventPayloadDto::getId, EventPayloadDto::getEvent_type,
            EventPayloadDto::setId);
    private static final RowSpec<BindingPayloadDto> BINDINGS = new RowSpec<>(
            "binding", BindingPayloadDto::getId, BindingPayloadDto::getName, BindingPayloadDto::setId);
    private static final RowSpec<ComponentCreateDto> COMPONENTS = new RowSpec<>(
            "component", ComponentCreateDto::getId, ComponentCreateDto::getName,
            ComponentCreateDto::setId);

    /**
     * Маркер спорного имени в карте алиасов (см. {@link #addAlias}). Настоящий алиас — всегда
     * {@code "id:" + Long}, поэтому маркер обязан быть тем, чем алиас быть не может: сразу за
     * префиксом стоит символ, которого в записи числа не бывает, и совпасть с настоящим ключом
     * маркер не способен ни при каком id.
     */
    private static final String AMBIGUOUS = "id:?ambiguous";

    private final ObjectMapper mapper;

    public SceneMerger(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SceneMerge merge(List<ComponentCreateDto> base, List<ComponentCreateDto> mine,
                            List<ComponentCreateDto> theirs) {
        List<MergeConflict> conflicts = new ArrayList<>();
        List<MergeChange> changes = new ArrayList<>();
        List<ComponentCreateDto> baseList = nullToEmpty(base);
        List<ComponentCreateDto> mineList = nullToEmpty(mine);
        List<ComponentCreateDto> theirsList = nullToEmpty(theirs);
        Map<String, String> rootAlias = nameAlias(baseList, theirsList, COMPONENTS);
        // Порядок компонентов верхнего уровня сцены — тот же вопрос, что и порядок детей внутри
        // компонента, просто без родителя: сравниваем его тем же способом (см. resolveOrder).
        List<String> rootOrder = resolveOrder(order(baseList, rootAlias), order(mineList, rootAlias),
                order(theirsList, rootAlias), "Сцена", conflicts);
        List<ComponentCreateDto> merged = applyOrder(
                mergeComponents(baseList, mineList, theirsList, "", conflicts, changes), rootOrder);
        return new SceneMerge(merged, conflicts, changes);
    }

    /**
     * Слияние списка строк одного вида. Ключ — id, при его отсутствии имя; порядок результата
     * берётся от моей стороны, чужие добавления дописываются в конец.
     */
    private <T> List<T> mergeRows(List<T> base, List<T> mine, List<T> theirs, RowSpec<T> spec,
                                  String path, List<MergeConflict> conflicts,
                                  List<MergeChange> changes) {
        Map<String, String> alias = nameAlias(base, theirs, spec);
        Map<String, T> baseByKey = byKey(base, spec, alias);
        Map<String, T> mineByKey = byKey(mine, spec, alias);
        Map<String, T> theirsByKey = byKey(theirs, spec, alias);

        List<T> result = new ArrayList<>();
        for (String key : allKeys(baseByKey, mineByKey, theirsByKey)) {
            T b = baseByKey.get(key);
            T m = mineByKey.get(key);
            T t = theirsByKey.get(key);
            String rowPath = path.isEmpty() ? label(key, spec, m, t, b)
                    : path + " / " + label(key, spec, m, t, b);

            Resolution<T> resolution = resolve(b, m, t, spec, rowPath, conflicts, changes);
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
     * правкой в другом его скрипте, хотя они друг другу не мешают — вложенные коллекции
     * сливаются отдельно, своим кодом, и своя же правка там даёт свою запись в
     * {@code changes}/{@code conflicts}.
     * <p>
     * Исключение — развилки «удалили ли меня»/«удалили ли их» ниже. Там вопрос не «отличается
     * ли поддерево целиком» (это дало бы ложный конфликт хоть на перестановке вложенных строк,
     * хоть на согласованном удалении с обеих сторон), а «есть ли внутри работа, которая
     * пропадёт вместе с удалением» — на это отвечает {@link #hasWorkToLose}. Текст самого
     * конфликта в тех же двух развилках строится по {@link #fullText}, а не по {@link #text}:
     * иначе человек увидел бы одинаковые base/yours и не понял бы, что теряет свою правку внутри.
     */
    private <T> Resolution<T> resolve(T b, T m, T t, RowSpec<T> spec, String path,
                                      List<MergeConflict> conflicts, List<MergeChange> changes) {
        // b/t уже сгруппированы с m под этим ключом в byKey — если m своего id не несёт, но у
        // b или t он есть, значит m попала сюда через алиас по имени (см. nameAlias), и её
        // идентичность нужно перенести на сам объект (см. javadoc stampAliasedId).
        stampAliasedId(m, b, t, spec);
        String entity = spec.entity();
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
            if (hasWorkToLose(b, t)) {
                conflicts.add(new MergeConflict(ConflictKind.DELETED_BY_YOU, entity, path,
                        fullText(b), null, fullText(t)));
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
            if (hasWorkToLose(b, m)) {
                conflicts.add(new MergeConflict(ConflictKind.DELETED_BY_THEM, entity, path,
                        fullText(b), fullText(m), null));
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

    /**
     * Переносит на {@code m} id, добытый только сопоставлением по алиасу (см. {@link
     * #nameAlias}): {@code b}/{@code t} уже сгруппированы с ней под общим ключом в {@link
     * #byKey}, а сама она id не несёт — единственный способ такой группировки без алиаса
     * невозможен, отдельного флага «сопоставлено по алиасу» поэтому не нужно.
     * <p>
     * Без переноса объект остаётся с {@code id == null} и после слияния: {@link #applyOrder}
     * не находит его в списке порядка (тот считался по алиасу, а сам объект — нет) и сортирует в
     * конец; {@code ComponentServiceImpl.deleteMissing} не видит его id в {@code keep} и удаляет
     * оригинал как отсутствующий в запросе; запись на диск заводит дубликат вместо обновления
     * существующей строки. Три следствия одной причины — чинятся переносом идентичности сюда,
     * в точку, где решение уже принято.
     */
    private <T> void stampAliasedId(T m, T b, T t, RowSpec<T> spec) {
        if (m == null || spec.idOf().apply(m) != null) {
            return;
        }
        Long id = b != null ? spec.idOf().apply(b) : null;
        if (id == null && t != null) {
            id = spec.idOf().apply(t);
        }
        if (id != null) {
            spec.setId().accept(m, id);
        }
    }

    /**
     * Сопоставление по ключу для одной стороны — коллизионно-безопасно относительно алиаса.
     * <p>
     * Строки с явным id разбираются первым проходом и застолбляют свои ключи; только после этого
     * id-less строка получает алиас — и то не любой, а лишь тот, что ещё не занят ни явным id, ни
     * другой id-less строкой той же стороны. Без этого второй порядок разбора совпал бы — та же
     * строка «Насос» без id отобрала бы ключ {@code "id:5"} у настоящего компонента 5 (обычный
     * {@link Map#put}, вторая запись бы тихо стёрла первую), и чистка/запись увидели бы только
     * одну сущность вместо двух, приняв вторую за первую и удалив весь её поддерево. Тот же
     * приём, что {@code ComponentScriptBindingApplier.applyProperties} (:198-217) уже использует
     * на записи: явные id — раньше сопоставления по имени.
     * <p>
     * Та же защита обязана работать и на голом {@code nameKey}, а не только на алиасе (I-3):
     * имена компонентов и биндингов не уникальны — ни ограничения в базе, ни проверки на записи
     * ({@code ComponentScriptBindingApplier} держит биндинги в {@code Map<String, List<Binding>>}
     * именно поэтому). Две мои id-less строки с одним именем, которого нет ни в базе, ни у «них»,
     * считали одинаковый ключ, и {@code Map.put} тихо стирал первую второй: одно из двух
     * добавлений пропадало вместе со всем поддеревом, а клиент получал 200. Столкнувшаяся строка
     * получает поэтому собственный синтетический ключ — не совпадающий ни с чем на других
     * сторонах, то есть остаётся тем, чем и выглядит: отдельным добавлением.
     */
    private <T> Map<String, T> byKey(List<T> rows, RowSpec<T> spec, Map<String, String> alias) {
        Map<String, T> byKey = new java.util.LinkedHashMap<>();
        Set<String> claimed = new java.util.HashSet<>();
        for (T row : nullToEmpty(rows)) {
            if (spec.idOf().apply(row) != null) {
                String key = keyOf(row, spec, alias);
                byKey.put(key, row);
                claimed.add(key);
            }
        }
        for (T row : nullToEmpty(rows)) {
            if (spec.idOf().apply(row) != null) {
                continue;
            }
            String nameKey = "name:" + nameKeyOf(row, spec);
            String aliased = aliasFor(alias, nameKey);
            String key = aliased != null && claimed.add(aliased) ? aliased : freeKey(nameKey, claimed);
            byKey.put(key, row);
        }
        return byKey;
    }

    /** Первый незанятый ключ на основе имени: сама строка, а не её однофамилец. */
    private String freeKey(String nameKey, Set<String> claimed) {
        if (claimed.add(nameKey)) {
            return nameKey;
        }
        for (int n = 2; ; n++) {
            String candidate = nameKey + "#" + n;
            if (claimed.add(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Ключ сопоставления. Строка с id адресуется им; без id — именем с префиксом, чтобы
     * «id=7» и «имя 7» не столкнулись в одной карте.
     */
    private <T> String keyOf(T row, RowSpec<T> spec) {
        return keyOf(row, spec, java.util.Map.of());
    }

    /**
     * То же, что {@link #keyOf(Object, RowSpec)}, с поправкой на пропущенный id: если строка его
     * не несёт, а {@code alias} (см. {@link #nameAlias}) знает эту сущность под чужим id, берём
     * его. Без этого строка без id (её сторона — сырой ввод клиента, для нетронутой строки он
     * законно может отсутствовать, см. {@link #nameAlias}) ложилась бы в карту под
     * {@code "name:..."}, а сопоставляемая с ней строка там, где id есть, — под {@code "id:..."},
     * и они никогда бы не встретились в {@link #allKeys}.
     */
    private <T> String keyOf(T row, RowSpec<T> spec, Map<String, String> alias) {
        Long id = spec.idOf().apply(row);
        if (id != null) {
            return "id:" + id;
        }
        String nameKey = "name:" + nameKeyOf(row, spec);
        String aliased = aliasFor(alias, nameKey);
        return aliased == null ? nameKey : aliased;
    }

    private <T> String nameKeyOf(T row, RowSpec<T> spec) {
        String key = spec.keyOf().apply(row);
        return key == null ? "" : key.trim();
    }

    /**
     * «Имя → id-ключ» по сторонам, где id есть всегда, когда сущность вообще существует: и база
     * (снимок версии), и «их» текущее состояние приходят из БД, а не из тела запроса. «Моя»
     * сторона — сырой {@code ComponentCreateDto} клиента, и для нетронутой строки id законно
     * может отсутствовать: остальной код проекта это уже допускает и сам сопоставляет по имени
     * ({@code ComponentScriptBindingApplier}, {@code applyStates} — id не пришёл, ищем среди
     * текущих строк компонента по имени). Без этого алиаса такая строка на «моей» стороне ложилась
     * бы под {@code "name:..."}, а на базовой/чужой — под {@code "id:..."}: слияние решало бы, что
     * я её удалил (в паре с базой) и тут же завёл заново (в паре с самой собой) — ложный
     * {@code DELETED_BY_YOU} там, где я строку вообще не трогал.
     * <p>
     * Складываются обе стороны сразу, а не одна база: независимое добавление одноимённой строки
     * с обеих сторон — это {@code BOTH_ADDED}, повод спросить человека, а не тихо задвоить строку
     * с тем же именем.
     */
    private <T> Map<String, String> nameAlias(List<T> base, List<T> theirs, RowSpec<T> spec) {
        Map<String, String> alias = new java.util.LinkedHashMap<>();
        addAlias(alias, base, spec);
        addAlias(alias, theirs, spec);
        return alias;
    }

    /**
     * Имя, за которое держатся два разных id, адресом быть перестаёт: алиас на него не выдаётся
     * вовсе (I-3).
     * <p>
     * Раньше это был обычный {@code put}, и при одноимённых строках алиас указывал на ту, чей id
     * попался последним. Моя строка без id уезжала тогда в произвольную из них — например, в
     * только что добавленного «ими» однофамильца, о котором я и не знал: чужое добавление
     * затиралось моим содержимым, а старая строка удалялась как «не присланная», и всё это с
     * ответом 200. Разрешить эту неоднозначность нечем — сопоставлять больше не по чему, — а
     * угадывать здесь значит молча испортить не ту строку.
     * <p>
     * Без алиаса моя id-less строка остаётся тем, чем выглядит: новой. Это ровно то, что делает с
     * ней сохранение вне слияния — {@code deleteMissing} собирает {@code keep} только из явных id,
     * и строка без id всегда создаётся заново. Слияние обязано вести себя так же, а не
     * сохранять произвольно выбранный id.
     */
    private <T> void addAlias(Map<String, String> alias, List<T> rows, RowSpec<T> spec) {
        for (T row : nullToEmpty(rows)) {
            Long id = spec.idOf().apply(row);
            if (id == null) {
                continue;
            }
            String nameKey = "name:" + nameKeyOf(row, spec);
            String idKey = "id:" + id;
            String known = alias.get(nameKey);
            alias.put(nameKey, known == null || known.equals(idKey) ? idKey : AMBIGUOUS);
        }
    }

    /**
     * Алиас имени либо {@code null}, если имени под алиасом нет или оно спорное (см.
     * {@link #addAlias}). Читать карту напрямую нельзя: маркер спорности — не ключ.
     */
    private String aliasFor(Map<String, String> alias, String nameKey) {
        String value = alias.get(nameKey);
        return AMBIGUOUS.equals(value) ? null : value;
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

    /**
     * Есть ли в {@code side} работа, которую унесёт с собой удаление родителя, если сравнивать с
     * {@code base}: свои скалярные поля отличаются, или во вложенной коллекции есть добавленная
     * строка, или есть строка под тем же ключом, что в базе, но с другим содержимым. Строка,
     * которая была в базе, а у {@code side} её нет, — это удаление самой строки, оно поглощается
     * удалением родителя и работой не считается: согласованное удаление обеими сторонами
     * (родителя целиком одной, строки внутри другой) конфликтом быть не должно.
     * <p>
     * Для компонентов проверка рекурсивна по {@code children}: правка на третьем уровне внутри
     * удалённой ветки терялась бы так же молча, как и на первом, не будь рекурсии.
     */
    private boolean hasWorkToLose(Object base, Object side) {
        if (!same(base, side)) {
            return true;
        }
        if (!(side instanceof ComponentCreateDto sideComponent)) {
            return false;
        }
        // base — того же типа T, что и side, во всех точках вызова resolve(); компонент
        // сравнивается только с компонентом.
        ComponentCreateDto baseComponent = (ComponentCreateDto) base;
        return hasNestedWork(rows(baseComponent, ComponentCreateDto::getProperties),
                    rows(sideComponent, ComponentCreateDto::getProperties), PROPERTIES,
                    (baseRow, row) -> !same(baseRow, row))
                || hasNestedWork(rows(baseComponent, ComponentCreateDto::getScripts),
                    rows(sideComponent, ComponentCreateDto::getScripts), SCRIPTS,
                    (baseRow, row) -> !same(baseRow, row))
                || hasNestedWork(rows(baseComponent, ComponentCreateDto::getStates),
                    rows(sideComponent, ComponentCreateDto::getStates), STATES,
                    (baseRow, row) -> !same(baseRow, row))
                || hasNestedWork(rows(baseComponent, ComponentCreateDto::getEvents),
                    rows(sideComponent, ComponentCreateDto::getEvents), EVENTS,
                    (baseRow, row) -> !same(baseRow, row))
                || hasNestedWork(rows(baseComponent, ComponentCreateDto::getBindings),
                    rows(sideComponent, ComponentCreateDto::getBindings), BINDINGS,
                    (baseRow, row) -> !same(baseRow, row))
                || hasNestedWork(rows(baseComponent, ComponentCreateDto::getChildren),
                    rows(sideComponent, ComponentCreateDto::getChildren), COMPONENTS,
                    this::hasWorkToLose);
    }

    /**
     * Есть ли среди {@code side} строка, сопоставленная по ключу (как в {@link #mergeRows}, не
     * по позиции — иначе перестановка строк внутри удаляемого компонента ложно выглядела бы
     * правкой), которой в {@code base} не было, либо строка, которую {@code isWork} признаёт
     * работой относительно её базовой пары.
     */
    private <T> boolean hasNestedWork(List<T> base, List<T> side, RowSpec<T> spec,
                                      BiPredicate<T, T> isWork) {
        // Только база, без «их»: здесь спрашивают «то же ли это, что было в базе», а не «то же
        // ли, что завели независимо с двух сторон» — второе разбирает mergeRows/mergeComponents.
        Map<String, String> alias = nameAlias(base, List.of(), spec);
        Map<String, T> baseByKey = byKey(base, spec, alias);
        for (T row : nullToEmpty(side)) {
            T baseRow = baseByKey.get(keyOf(row, spec, alias));
            if (baseRow == null || isWork.test(baseRow, row)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Каноническое содержимое без вложенных коллекций и без {@code id}. Идентичность сущности к
     * этому моменту уже решена сопоставлением по ключу ({@link #keyOf}/{@link #nameAlias}) —
     * сравнивать {@code id} здесь второй раз как содержимое нельзя: у строки без id (см.
     * {@link #nameAlias}) он в каноническом виде попросту отсутствует, а у сопоставленной с ней
     * пары на базовой/чужой стороне — присутствует, и разница по единственному полю {@code id}
     * выглядела бы как правка, которой не было.
     */
    private JsonNode scalars(Object value) {
        JsonNode node = MergeShape.canonical(mapper.valueToTree(value));
        if (node != null && node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = node.deepCopy();
            copy.remove(List.of("children", "properties", "scripts", "states", "events", "bindings"));
            copy.remove("id");
            return copy;
        }
        return node;
    }

    private String text(Object value) {
        JsonNode node = scalars(value);
        return node == null ? null : node.toString();
    }

    /** То же, что {@link #text}, но по полному каноническому виду — вложенные коллекции видны. */
    private String fullText(Object value) {
        JsonNode node = MergeShape.canonical(mapper.valueToTree(value));
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
        Map<String, String> alias = nameAlias(base, theirs, COMPONENTS);
        Map<String, ComponentCreateDto> baseByKey = byKey(base, COMPONENTS, alias);
        Map<String, ComponentCreateDto> mineByKey = byKey(mine, COMPONENTS, alias);
        Map<String, ComponentCreateDto> theirsByKey = byKey(theirs, COMPONENTS, alias);

        List<ComponentCreateDto> result = new ArrayList<>();
        for (String key : allKeys(baseByKey, mineByKey, theirsByKey)) {
            ComponentCreateDto b = baseByKey.get(key);
            ComponentCreateDto m = mineByKey.get(key);
            ComponentCreateDto t = theirsByKey.get(key);
            String componentPath = path.isEmpty() ? label(key, COMPONENTS, m, t, b)
                    : path + " / " + label(key, COMPONENTS, m, t, b);

            Resolution<ComponentCreateDto> resolution =
                    resolve(b, m, t, COMPONENTS, componentPath, conflicts, changes);
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
            List<String> childOrder = checkChildrenOrder(b, m, t, componentPath, conflicts);
            merged.setChildren(applyOrder(mergeComponents(
                    rows(b, ComponentCreateDto::getChildren),
                    rows(m, ComponentCreateDto::getChildren),
                    rows(t, ComponentCreateDto::getChildren),
                    componentPath, conflicts, changes), childOrder));
            result.add(merged);
        }
        return result;
    }

    private <T> List<T> rows(ComponentCreateDto component, Function<ComponentCreateDto, List<T>> of) {
        return component == null ? List.of() : nullToEmpty(of.apply(component));
    }

    /**
     * Порядок детей конкретного компонента на трёх сторонах — тонкая обвязка над
     * {@link #resolveOrder} для случая с родителем; для родителя нет применяется тот же алгоритм
     * прямо к трём спискам (см. {@link #merge}).
     *
     * @return целевой порядок для итогового списка либо {@code null}, если родителя нет хотя бы
     *         на одной стороне (сравнивать нечего — этот случай уже разбирает основной алгоритм)
     *         или порядок слияния по умолчанию годится как есть
     */
    private List<String> checkChildrenOrder(ComponentCreateDto b, ComponentCreateDto m,
                                            ComponentCreateDto t, String path,
                                            List<MergeConflict> conflicts) {
        if (b == null || m == null || t == null) {
            return null;
        }
        Map<String, String> alias = nameAlias(rows(b, ComponentCreateDto::getChildren),
                rows(t, ComponentCreateDto::getChildren), COMPONENTS);
        return resolveOrder(order(b, alias), order(m, alias), order(t, alias), path, conflicts);
    }

    /**
     * Одновременная перестановка с двух сторон. Разрешить её автоматически нельзя: обе
     * очерёдности осмысленны, а выбрать за человека — значит молча испортить порядок отрисовки.
     * Перестановка одной стороной конфликтом не считается — там спорить не с кем, но применить
     * её к результату всё равно нужно: слияние по умолчанию всегда берёт порядок моей стороны
     * (см. {@link #allKeys}), и без явного навязывания чужая перестановка терялась бы молча.
     *
     * @return порядок, который нужно навязать результату («их», если переставили только они),
     *         либо {@code null}, если результату годится порядок слияния по умолчанию
     */
    private List<String> resolveOrder(List<String> baseOrder, List<String> myOrder,
                                      List<String> theirOrder, String path,
                                      List<MergeConflict> conflicts) {
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
            return null;
        }
        if (mineCommon.equals(baseCommon) && !theirsCommon.equals(baseCommon)) {
            return theirOrder;
        }
        return null;
    }

    /**
     * Применяет целевой порядок ключей к уже слитому списку. Ключи, которых в {@code order} нет
     * (свежие добавления — сам порядок сравнивает только общую часть, см. {@link #resolveOrder}),
     * остаются на месте, которое им дало слияние по умолчанию: сортировка стабильна.
     */
    private List<ComponentCreateDto> applyOrder(List<ComponentCreateDto> merged, List<String> order) {
        if (order == null) {
            return merged;
        }
        List<ComponentCreateDto> reordered = new ArrayList<>(merged);
        reordered.sort(Comparator.comparingInt(component -> {
            int index = order.indexOf(keyOf(component, COMPONENTS));
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        return reordered;
    }

    private List<String> order(ComponentCreateDto component, Map<String, String> alias) {
        return order(rows(component, ComponentCreateDto::getChildren), alias);
    }

    private List<String> order(List<ComponentCreateDto> components, Map<String, String> alias) {
        return nullToEmpty(components).stream()
                .map(component -> keyOf(component, COMPONENTS, alias))
                .collect(Collectors.toList());
    }
}
