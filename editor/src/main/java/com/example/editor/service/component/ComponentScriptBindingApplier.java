package com.example.editor.service.component;

import com.example.editor.dto.component.BindingPayloadDto;
import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.dto.component.EventPayloadDto;
import com.example.editor.dto.component.ScriptCreateDto;
import com.example.editor.dto.property.PropertyCreateDto;
import com.example.editor.model.component.Binding;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentEvent;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.component.EventTypes;
import com.example.editor.model.component.Script;
import com.example.editor.repository.component.ComponentPropertyRepository;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Синхронизирует коллекции scripts/bindings/events с DTO при создании/обновлении дерева
 * компонентов.
 */
@UtilityClass
public class ComponentScriptBindingApplier {

    /**
     * Ключ сопоставления по имени — общий для обеих сторон. Входящее имя тримится, а имя из БД
     * до этого бралось как есть, поэтому строка, сохранённая с краевым пробелом до появления
     * {@code trim}'а, не находилась и пересоздавалась при каждом сохранении (scada-w51).
     * Через API такую строку теперь не завести, но легаси-данные никто не чистил.
     */
    public String matchKey(String name) {
        return name == null ? null : name.trim();
    }

    /**
     * Освобождает ключи (имена, типы событий), которые переименование отдаёт другим строкам того
     * же запроса, — переводя переименовываемые строки через временный ключ.
     * <p>
     * Итоговое состояние {@code UNIQUE (component_id, name)} никогда не нарушает, а вот путь к
     * нему нарушает: Hibernate на flush выполняет вставки раньше обновлений, а порядок обновлений
     * между собой не определён вовсе. Поэтому «переименовать A и создать новую строку под
     * освободившимся именем» падало на INSERT, а обмен именами между двумя существующими
     * строками — на одном из двух UPDATE. Порядок внутри flush не настраивается, поэтому
     * освобождение делается отдельным ходом: переименовываемые строки получают временный ключ,
     * запись сбрасывается в базу, и только потом ставятся настоящие ключи (scada-wob).
     * <p>
     * Промежуточный flush стоит денег, поэтому делается только когда ключ действительно оспорен:
     * кто-то в этом же запросе претендует на ключ, который освобождает переименование. Обычное
     * сохранение и обычное переименование идут прежним путём, без единого лишнего запроса.
     * <p>
     * Удаления сюда не относятся: {@code orphanRemoval} Hibernate выполняет <b>раньше</b>
     * вставок, поэтому «удалить строку и создать новую под её именем» работает и без всего этого.
     *
     * @param originalKeys ключ каждой существующей строки ДО применения dto
     * @param setKey       присвоение ключа сущности — им ставится временное значение и обратно
     * @param tempKeyOf    временный ключ по номеру: у имён это случайная строка, у типов событий —
     *                     свободное значение домена, потому что там стоит CHECK
     * @param flush        сброс персистентного контекста в базу
     */
    public <T> void freeContestedKeys(List<T> incoming, Map<Long, String> originalKeys,
                                      Function<T, Long> idOf, Function<T, String> keyOf,
                                      BiConsumer<T, String> setKey, IntFunction<String> tempKeyOf,
                                      Runnable flush) {
        Map<T, String> finalKeys = new IdentityHashMap<>();
        Set<String> freed = new HashSet<>();
        for (T item : incoming) {
            String key = keyOf.apply(item);
            finalKeys.put(item, key);
            Long id = idOf.apply(item);
            if (id == null) {
                continue;
            }
            String original = originalKeys.get(id);
            if (original != null && !original.equals(key)) {
                freed.add(original);
            }
        }
        if (freed.isEmpty()) {
            return;
        }
        boolean contested = finalKeys.values().stream().anyMatch(freed::contains);
        if (!contested) {
            return;
        }

        int index = 0;
        for (T item : incoming) {
            Long id = idOf.apply(item);
            if (id == null) {
                continue;
            }
            String original = originalKeys.get(id);
            if (original != null && !original.equals(finalKeys.get(item))) {
                setKey.accept(item, tempKeyOf.apply(index++));
            }
        }
        flush.run();
        for (T item : incoming) {
            setKey.accept(item, finalKeys.get(item));
        }
    }

    /**
     * Временные ключи для имён: домен свободный, поэтому годится случайная строка. Префикс
     * случаен, чтобы не столкнуться ни с настоящим именем, ни с временным ключом соседней
     * транзакции, переименовывающей строки того же компонента; длина держится в пределах самой
     * узкой колонки.
     */
    public IntFunction<String> temporaryNames() {
        String prefix = "~" + UUID.randomUUID().toString().substring(0, 8) + "-";
        return index -> prefix + index;
    }

    /**
     * Временные ключи для типов событий: выдуманное значение здесь не пройдёт — на колонке стоит
     * {@code CHECK} по списку {@link EventTypes#ALL} (и ослаблять его ради перестановки нельзя,
     * он и держит тип события от превращения в свободную строку). Поэтому временное значение
     * берётся из того же домена — из типов, которых нет ни среди прежних, ни среди присланных.
     * <p>
     * Свободного не найдётся только у компонента, занявшего все восемь типов сразу. Тогда —
     * внятный отказ, а не 500 с констрейнтом наружу.
     */
    private IntFunction<String> spareEventTypes(Component entity, Collection<String> original,
                                                Collection<String> incoming) {
        Set<String> occupied = new HashSet<>(original);
        occupied.addAll(incoming);
        List<String> spare = EventTypes.ALL.stream()
                .filter(type -> !occupied.contains(type))
                .sorted()
                .toList();
        return index -> {
            if (index >= spare.size()) {
                throw new IllegalStateException(
                        "Cannot rearrange event types of component " + entity.getId()
                                + ": all event types are taken, so there is no free slot to move"
                                + " a handler through; remove one handler first, then rearrange");
            }
            return spare.get(index);
        };
    }

    /**
     * Массовая синхронизация свойств компонента (строки таблицы и т.п.) из DTO.
     * <p>
     * В отличие от scripts/bindings/events трогаем <b>только если поле прислано</b>
     * ({@code properties != null}): при {@code null} существующие свойства не меняются, чтобы не
     * ломать их ведение через отдельный {@code ComponentPropertyController}. Прислали список —
     * он задаёт набор строк целиком: чего в нём нет, то удаляется.
     * <p>
     * <b>Сопоставление — сначала присланный id, затем имя, строки переиспользуются, а не
     * пересоздаются.</b> Раньше здесь стоял {@code clear()} с последующей вставкой новых
     * сущностей, и у всех строк при каждом сохранении таблицы менялись id. Ломалось от этого
     * многое: биндинг, присланный тем же запросом, ссылался на уже удалённое свойство и валил
     * запрос в 500 ({@code TransientObjectException} из недр Hibernate, без внятного сообщения
     * наружу); а значениям наборов пришлось искать свою строку по имени, потому что id под ними
     * уезжал. Имя и так ключ строки везде — по нему адресуют {@code RecipeValue} и
     * {@code writeTag}, — поэтому без id оно же служит ключом сопоставления здесь. Имена
     * приводятся {@code trim}'ом и обязаны быть уникальными в пределах компонента: два
     * одноимённых свойства сделали бы все эти привязки неоднозначными.
     * <p>
     * {@code position} — номер для представления: если фронт его не прислал, проставляем по
     * позиции в массиве.
     * <p>
     * Переименование по id этот метод распознаёт (строка не пересоздаётся), но значения набора
     * на новое имя не переносит — меняется только {@code ComponentProperty.name},
     * {@code recipe_value.row_name} остаётся прежним. Перенос делает только точечный
     * {@code ComponentPropertyServiceImpl.update} (см. его javadoc и {@code scada-v3g}).
     */
    public void applyProperties(Component entity, ComponentCreateDto dto, Runnable flush) {
        if (dto.getProperties() == null) {
            return;
        }
        Map<String, ComponentProperty> existingByName = new HashMap<>();
        Map<Long, ComponentProperty> existingById = new HashMap<>();
        Map<Long, String> originalNames = new HashMap<>();
        for (ComponentProperty existing : entity.getProperties()) {
            existingByName.put(matchKey(existing.getName()), existing);
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
                originalNames.put(existing.getId(), matchKey(existing.getName()));
            }
        }

        Set<Long> keptIds = new HashSet<>();
        // Первый проход — только явные id: они старше сопоставления по имени. Иначе элемент без
        // id, пришедший раньше по списку, забрал бы строку, которую следующий элемент адресует
        // по id, и две разные строки молча слились бы в одну. Тот же приём, что в applyScripts.
        Map<PropertyCreateDto, ComponentProperty> resolved = new IdentityHashMap<>();
        for (PropertyCreateDto p : dto.getProperties()) {
            if (p.getId() == null) {
                continue;
            }
            ComponentProperty target = existingById.get(p.getId());
            if (target == null) {
                throw new IllegalStateException(
                        "Property " + p.getId() + " does not belong to component " + entity.getId());
            }
            if (!keptIds.add(target.getId())) {
                throw new IllegalStateException(
                        "Property " + p.getId() + " is addressed twice in the same request");
            }
            existingByName.remove(matchKey(target.getName()));
            resolved.put(p, target);
        }

        List<ComponentProperty> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        int index = 0;
        for (PropertyCreateDto p : dto.getProperties()) {
            if (p.getName() == null || p.getName().isBlank()) {
                throw new IllegalStateException("Property name is required");
            }
            String name = p.getName().trim();
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate property name '" + name + "' in component " + entity.getId()
                                + "; names must be unique within a component");
            }
            if (p.getProperty_type() == null || p.getProperty_type().isBlank()) {
                throw new IllegalStateException("Property property_type is required for '" + name + "'");
            }
            if (p.getValue_type() == null || p.getValue_type().isBlank()) {
                throw new IllegalStateException("Property value_type is required for '" + name + "'");
            }

            ComponentProperty target = p.getId() != null ? resolved.get(p) : existingByName.remove(name);
            if (target == null) {
                target = new ComponentProperty();
                target.setComponent(entity);
            }
            target.setName(name);
            target.setTagId(p.getTag_id());
            target.setPropertyType(p.getProperty_type());
            target.setDescription(p.getDescription());
            target.setValueType(p.getValue_type());
            target.setDefaultValue(p.getDefault_value());
            target.setPosition(p.getPosition() != null ? p.getPosition() : index);
            target.setLogging(p.isLogging());
            target.setOnChange(p.getOnChange());
            if (target.getId() != null) {
                keptIds.add(target.getId());
            }
            incoming.add(target);
            index++;
        }

        freeContestedKeys(incoming, originalNames, ComponentProperty::getId,
                cp -> matchKey(cp.getName()), ComponentProperty::setName, temporaryNames(), flush);

        entity.getProperties().removeIf(existing -> existing.getId() != null
                ? !keptIds.contains(existing.getId())
                : !seenNames.contains(matchKey(existing.getName())));
        for (ComponentProperty target : incoming) {
            if (target.getId() == null) {
                entity.getProperties().add(target);
            }
        }
    }

    /**
     * @param flush сброс контекста в базу; нужен, чтобы освободить имя, отданное переименованием
     *              другой строке того же запроса (см. {@link #freeContestedKeys})
     */
    public void apply(
            Component entity,
            ComponentCreateDto dto,
            ComponentPropertyRepository propertyRepository,
            Runnable flush
    ) {
        applyScripts(entity, dto, flush);
        applyBindings(entity, dto, propertyRepository);
        applyEvents(entity, dto, flush);
    }

    /**
     * Синхронизация серверных скриптов. Скрипт с тем же именем переиспользуется, а не
     * пересоздаётся: {@code Script.id} — это {@code scriptId} в {@code {"type":"ACTION","scriptId":N}},
     * а сессия мониторинга берёт дерево проекта один раз при старте и живёт с ним часами. Пока
     * id пересоздавались, сохранение сцены посреди смены обесценивало все id в уже открытых
     * сессиях: оператор жал кнопку, ACTION уходил со старым номером, скрипт не находился —
     * и клапан просто не срабатывал, без ошибки на экране.
     * <p>
     * Имя здесь и так адрес: {@code runScript('Открыть клапан')} на фронте ищет скрипт по имени.
     * Поэтому одноимённые скрипты отвергаем — они делали бы неоднозначным и вызов, и это
     * сопоставление.
     */
    private void applyScripts(Component entity, ComponentCreateDto dto, Runnable flush) {
        if (dto.getScripts() == null) {
            entity.getScripts().clear();
            return;
        }
        Map<String, Script> existingByName = new HashMap<>();
        Map<Long, Script> existingById = new HashMap<>();
        Map<Long, String> originalNames = new HashMap<>();
        for (Script existing : entity.getScripts()) {
            existingByName.put(matchKey(existing.getName()), existing);
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
                originalNames.put(existing.getId(), matchKey(existing.getName()));
            }
        }

        Set<Long> keptIds = new HashSet<>();
        // Первый проход — только явные id: они старше сопоставления по имени. Иначе элемент
        // без id, пришедший раньше по списку, успел бы забрать строку, которую следующий элемент
        // адресует по id, и две разные сущности молча слились бы в одну (одна из них теряется).
        Map<ScriptCreateDto, Script> resolved = new IdentityHashMap<>();
        for (ScriptCreateDto s : dto.getScripts()) {
            if (s.getId() == null) {
                continue;
            }
            Script target = existingById.get(s.getId());
            if (target == null) {
                throw new IllegalStateException(
                        "Script " + s.getId() + " does not belong to component " + entity.getId());
            }
            if (!keptIds.add(target.getId())) {
                throw new IllegalStateException(
                        "Script " + s.getId() + " is addressed twice in the same request");
            }
            // Забрали строку по id — её прежнее имя перестаёт быть ключом. Иначе элемент без id
            // под старым именем найдёт её же и отберёт обратно: переименование молча отменится,
            // а вторая сущность не создастся.
            existingByName.remove(matchKey(target.getName()));
            resolved.put(s, target);
        }

        List<Script> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (ScriptCreateDto s : dto.getScripts()) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new IllegalStateException("Script name is required");
            }
            String name = s.getName().trim();
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Duplicate script name '" + name + "' in component " + entity.getId()
                                + "; runScript() addresses scripts by name, so names must be unique");
            }
            Script target = s.getId() != null ? resolved.get(s) : existingByName.remove(name);
            if (target == null) {
                target = new Script();
                target.setComponent(entity);
            }
            target.setName(name);
            target.setScript(s.getScript());
            if (target.getId() != null) {
                keptIds.add(target.getId());
            }
            incoming.add(target);
        }

        freeContestedKeys(incoming, originalNames, Script::getId,
                s -> matchKey(s.getName()), Script::setName, temporaryNames(), flush);

        entity.getScripts().removeIf(existing -> existing.getId() != null
                ? !keptIds.contains(existing.getId())
                : !seenNames.contains(matchKey(existing.getName())));
        for (Script target : incoming) {
            if (target.getId() == null) {
                entity.getScripts().add(target);
            }
        }
    }

    /**
     * Синхронизация биндингов. Раньше здесь стоял {@code clear()} с повторной вставкой, и
     * {@code Binding.id} менялся на каждом сохранении сцены (scada-dna). Ключ сопоставления —
     * сначала присланный id, затем имя: имя биндинга уникальным не объявлено, поэтому
     * одноимённые разбираются по порядку — первый свободный с этим именем.
     */
    private void applyBindings(
            Component entity,
            ComponentCreateDto dto,
            ComponentPropertyRepository propertyRepository
    ) {
        if (dto.getBindings() == null) {
            entity.getBindings().clear();
            return;
        }
        Map<Long, Binding> existingById = new HashMap<>();
        Map<String, List<Binding>> existingByName = new HashMap<>();
        for (Binding existing : entity.getBindings()) {
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
            }
            existingByName
                    .computeIfAbsent(matchKey(existing.getName()), k -> new ArrayList<>())
                    .add(existing);
        }

        Set<Long> keptIds = new HashSet<>();
        // Первый проход — только явные id: они старше сопоставления по имени. Иначе элемент
        // без id, пришедший раньше по списку, успел бы забрать строку, которую следующий элемент
        // адресует по id, и две разные сущности молча слились бы в одну (одна из них теряется).
        Map<BindingPayloadDto, Binding> resolved = new IdentityHashMap<>();
        for (BindingPayloadDto b : dto.getBindings()) {
            if (b.getId() == null) {
                continue;
            }
            Binding target = existingById.get(b.getId());
            if (target == null) {
                throw new IllegalStateException(
                        "Binding " + b.getId() + " does not belong to component " + entity.getId());
            }
            if (!keptIds.add(target.getId())) {
                throw new IllegalStateException(
                        "Binding " + b.getId() + " is addressed twice in the same request");
            }
            // Забрали строку по id — она не должна оставаться доступной кандидатом для
            // сопоставления по имени.
            List<Binding> sameName = existingByName.get(matchKey(target.getName()));
            if (sameName != null) {
                sameName.remove(target);
            }
            resolved.put(b, target);
        }

        List<Binding> incoming = new ArrayList<>();
        for (BindingPayloadDto b : dto.getBindings()) {
            Binding target;
            if (b.getId() != null) {
                target = resolved.get(b);
            } else {
                target = null;
                List<Binding> sameName = existingByName.get(matchKey(b.getName()));
                if (sameName != null) {
                    for (Binding candidate : sameName) {
                        if (candidate.getId() == null || !keptIds.contains(candidate.getId())) {
                            target = candidate;
                            break;
                        }
                    }
                }
            }
            if (target == null) {
                target = new Binding();
                target.setComponent(entity);
            }
            target.setName(b.getName());
            target.setScript(b.getScript());
            target.setComponentProperty(resolveBindingProperty(entity, b, propertyRepository));
            if (target.getId() != null) {
                keptIds.add(target.getId());
            }
            incoming.add(target);
        }

        entity.getBindings().removeIf(existing ->
                existing.getId() != null ? !keptIds.contains(existing.getId())
                        : !incoming.contains(existing));
        for (Binding target : incoming) {
            if (target.getId() == null && !entity.getBindings().contains(target)) {
                entity.getBindings().add(target);
            }
        }
    }

    /**
     * Свойство, к которому привязывается биндинг. Ищется <b>среди свойств самого компонента</b>,
     * уже приведённых к присланному виду (см. {@link #applyProperties}) — то есть в том же
     * состоянии, в каком они уйдут в базу. Прежняя версия брала свойство глобальным
     * {@code findById} из репозитория, и когда тот же запрос нёс ещё и {@code properties[]},
     * биндинг получал строку, которую синхронизация свойств уже выбросила: Hibernate валился на
     * flush с {@code TransientObjectException}, а наружу уходил безликий 500.
     * <p>
     * Адрес — id либо имя (см. {@link BindingPayloadDto}). Имя нужно для строки, создаваемой этим
     * же запросом: id у неё появится только после flush, а привязать биндинг надо сейчас.
     * Репозиторий остаётся нужен ровно для одного — отличить «чужое свойство» от «своего, но
     * удаляемого этим запросом» и сказать об этом внятно вместо общей ошибки.
     */
    private ComponentProperty resolveBindingProperty(
            Component entity,
            BindingPayloadDto b,
            ComponentPropertyRepository propertyRepository
    ) {
        Long propertyId = b.getComponent_property_id();
        if (propertyId != null) {
            for (ComponentProperty property : entity.getProperties()) {
                if (propertyId.equals(property.getId())) {
                    return property;
                }
            }
            ComponentProperty stored = propertyRepository.findById(propertyId).orElseThrow(
                    () -> new IllegalStateException("Component property not found: " + propertyId));
            Long ownerId = stored.getComponent() == null ? null : stored.getComponent().getId();
            if (!Objects.equals(ownerId, entity.getId())) {
                throw new IllegalStateException(
                        "Binding targets property " + propertyId
                                + " which does not belong to component " + entity.getId());
            }
            throw new IllegalStateException(
                    "Binding targets property " + propertyId + " ('" + stored.getName()
                            + "'), which is not among the properties sent for component " + entity.getId()
                            + "; send that row in properties[] or address the binding by component_property_name");
        }

        String propertyName = b.getComponent_property_name() == null
                ? null : b.getComponent_property_name().trim();
        if (propertyName == null || propertyName.isEmpty()) {
            throw new IllegalStateException(
                    "Binding requires component_property_id or component_property_name");
        }
        for (ComponentProperty property : entity.getProperties()) {
            if (propertyName.equals(property.getName())) {
                return property;
            }
        }
        throw new IllegalStateException(
                "Binding targets property named '" + propertyName
                        + "', which component " + entity.getId() + " does not have");
    }

    /**
     * Синхронизация обработчиков событий. Как и у свойств, обработчик того же типа
     * <b>переиспользуется</b>, а не пересоздаётся — но здесь на этом держится сама
     * работоспособность сохранения, а не только стабильность id.
     * <p>
     * На {@code component_event} висит UNIQUE {@code (component_id, event_type)}. Прежний
     * {@code clear()} + вставка нового обработчика того же типа порождали в одной транзакции
     * DELETE старой строки и INSERT новой с тем же ключом, а Hibernate на flush выполняет
     * вставки <b>раньше</b> удалений — и запрос падал на нарушении уникальности. Ломалось
     * ровно то, что делают чаще всего: пересохранение сцены из редактора, где у кнопки уже
     * есть {@code onClick}. Порядок операций внутри flush менять нельзя, поэтому убираем сам
     * повод для конфликта: строка остаётся той же, меняется только её {@code script}.
     * <p>
     * {@code events == null} по-прежнему означает «обработчиков нет»: узел сохраняется целиком,
     * и отсутствующее поле — это удаление, а не «не трогать» (в отличие от {@code properties}).
     */
    private void applyEvents(Component entity, ComponentCreateDto dto, Runnable flush) {
        if (dto.getEvents() == null) {
            entity.getEvents().clear();
            return;
        }
        Map<String, ComponentEvent> existingByType = new HashMap<>();
        Map<Long, ComponentEvent> existingById = new HashMap<>();
        Map<Long, String> originalTypes = new HashMap<>();
        for (ComponentEvent existing : entity.getEvents()) {
            existingByType.put(existing.getEventType(), existing);
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
                originalTypes.put(existing.getId(), existing.getEventType());
            }
        }

        Set<Long> keptIds = new HashSet<>();
        // Первый проход — только явные id: они старше сопоставления по типу. Иначе элемент
        // без id, пришедший раньше по списку, успел бы забрать строку, которую следующий элемент
        // адресует по id, и две разные сущности молча слились бы в одну (одна из них теряется).
        Map<EventPayloadDto, ComponentEvent> resolved = new IdentityHashMap<>();
        for (EventPayloadDto e : dto.getEvents()) {
            if (e.getId() == null) {
                continue;
            }
            ComponentEvent target = existingById.get(e.getId());
            if (target == null) {
                throw new IllegalStateException(
                        "Event " + e.getId() + " does not belong to component " + entity.getId());
            }
            if (!keptIds.add(target.getId())) {
                throw new IllegalStateException(
                        "Event " + e.getId() + " is addressed twice in the same request");
            }
            // Забрали строку по id — её прежний тип перестаёт быть ключом. Иначе следующий
            // элемент того же запроса, пришедший без id под старым типом, найдёт её же и
            // отберёт обратно.
            existingByType.remove(target.getEventType());
            resolved.put(e, target);
        }

        List<ComponentEvent> incoming = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        for (EventPayloadDto e : dto.getEvents()) {
            if (!EventTypes.isValid(e.getEvent_type())) {
                throw new IllegalStateException(
                        "Unknown event_type: " + e.getEvent_type() + "; allowed: " + EventTypes.ALL);
            }
            if (!seenTypes.add(e.getEvent_type())) {
                throw new IllegalStateException(
                        "Duplicate event_type " + e.getEvent_type() + " for component " + entity.getId());
            }
            if (e.getScript() == null || e.getScript().isBlank()) {
                throw new IllegalStateException(
                        "Event " + e.getEvent_type() + " requires a script; omit the event to remove it");
            }

            ComponentEvent target = e.getId() != null
                    ? resolved.get(e) : existingByType.remove(e.getEvent_type());
            if (target == null) {
                target = new ComponentEvent();
                target.setComponent(entity);
            }
            target.setEventType(e.getEvent_type());
            target.setScript(e.getScript());
            if (target.getId() != null) {
                keptIds.add(target.getId());
            }
            incoming.add(target);
        }

        freeContestedKeys(incoming, originalTypes, ComponentEvent::getId,
                ComponentEvent::getEventType, ComponentEvent::setEventType,
                spareEventTypes(entity, originalTypes.values(), seenTypes), flush);

        entity.getEvents().removeIf(existing -> existing.getId() != null
                ? !keptIds.contains(existing.getId())
                : !seenTypes.contains(existing.getEventType()));
        for (ComponentEvent target : incoming) {
            if (target.getId() == null) {
                entity.getEvents().add(target);
            }
        }
    }
}
