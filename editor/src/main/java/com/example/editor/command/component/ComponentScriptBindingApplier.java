package com.example.editor.command.component;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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
     * Отвергает обмен именами внутри одного запроса: имя, освобождаемое переименованием
     * существующей строки, одновременно занимается новой строкой.
     * <p>
     * Записать это нельзя: Hibernate на flush выполняет вставки раньше обновлений, поэтому
     * INSERT новой строки упирается в {@code UNIQUE (component_id, name)} прежде, чем UPDATE
     * освободит имя. Порядок операций внутри flush менять нельзя, поэтому ловим до записи и
     * объясняем, что делать. Полноценная поддержка — {@code scada-wob}.
     *
     * @param originalKeys ключ (имя либо тип) каждой существующей строки ДО применения dto
     * @param what         человекочитаемое название сущности для сообщения
     */
    public <T> void rejectNameSwaps(List<T> incoming, Map<Long, String> originalKeys,
                                    Function<T, Long> idOf, Function<T, String> keyOf,
                                    String what) {
        Set<String> freed = new HashSet<>();
        for (T item : incoming) {
            Long id = idOf.apply(item);
            if (id == null) {
                continue;
            }
            String original = originalKeys.get(id);
            if (original != null && !original.equals(keyOf.apply(item))) {
                freed.add(original);
            }
        }
        for (T item : incoming) {
            if (idOf.apply(item) == null && freed.contains(keyOf.apply(item))) {
                throw new IllegalStateException(
                        what + " '" + keyOf.apply(item) + "' is freed by a rename in the same"
                                + " request and taken by a new one; save the rename first,"
                                + " then create the new one");
            }
        }
    }

    /**
     * Массовая синхронизация свойств компонента (строки таблицы и т.п.) из DTO.
     * <p>
     * В отличие от scripts/bindings/events трогаем <b>только если поле прислано</b>
     * ({@code properties != null}): при {@code null} существующие свойства не меняются, чтобы не
     * ломать их ведение через отдельный {@code ComponentPropertyController}. Прислали список —
     * он задаёт набор строк целиком: чего в нём нет, то удаляется.
     * <p>
     * <b>Сопоставление идёт по имени, а строки переиспользуются, а не пересоздаются.</b> Раньше
     * здесь стоял {@code clear()} с последующей вставкой новых сущностей, и у всех строк при
     * каждом сохранении таблицы менялись id. Ломалось от этого многое: биндинг, присланный тем же
     * запросом, ссылался на уже удалённое свойство и валил запрос в 500
     * ({@code TransientObjectException} из недр Hibernate, без внятного сообщения наружу); а
     * значениям наборов пришлось искать свою строку по имени, потому что id под ними уезжал.
     * Имя и так ключ строки везде — по нему адресуют {@code RecipeValue} и {@code writeTag}, —
     * поэтому оно же служит ключом сопоставления здесь. Имена приводятся {@code trim}'ом и
     * обязаны быть уникальными в пределах компонента: два одноимённых свойства сделали бы все
     * эти привязки неоднозначными.
     * <p>
     * {@code position} — номер для представления: если фронт его не прислал, проставляем по
     * позиции в массиве. Переименование строки от «удалили одну, добавили другую» здесь
     * по-прежнему неотличимо, поэтому значения наборов не переносятся — для переименования есть
     * точечный {@code ComponentPropertyController} (см. {@code ComponentPropertyServiceImpl.update}).
     */
    public void applyProperties(Component entity, ComponentCreateDto dto) {
        if (dto.getProperties() == null) {
            return;
        }
        Map<String, ComponentProperty> existingByName = new HashMap<>();
        for (ComponentProperty existing : entity.getProperties()) {
            existingByName.put(matchKey(existing.getName()), existing);
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

            // Строка с таким именем уже есть — обновляем её на месте, сохраняя id.
            ComponentProperty target = existingByName.get(name);
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
            incoming.add(target);
            index++;
        }

        // Сначала убираем выпавшие строки (orphanRemoval их удалит), затем добавляем новые.
        entity.getProperties().removeIf(existing -> !seenNames.contains(matchKey(existing.getName())));
        for (ComponentProperty target : incoming) {
            if (target.getId() == null) {
                entity.getProperties().add(target);
            }
        }
    }

    public void apply(
            Component entity,
            ComponentCreateDto dto,
            ComponentPropertyRepository propertyRepository
    ) {
        applyScripts(entity, dto);
        applyBindings(entity, dto, propertyRepository);
        applyEvents(entity, dto);
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
    private void applyScripts(Component entity, ComponentCreateDto dto) {
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

        List<Script> incoming = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<Long> keptIds = new HashSet<>();
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
            Script target = s.getId() == null ? null : existingById.get(s.getId());
            if (target == null && s.getId() != null) {
                throw new IllegalStateException(
                        "Script " + s.getId() + " does not belong to component " + entity.getId());
            }
            if (target != null) {
                // Забрали строку по id — её прежнее имя перестаёт быть ключом. Иначе следующий
                // элемент того же запроса, пришедший без id под старым именем, найдёт её же и
                // отберёт обратно: переименование молча отменится, а вторая сущность не создастся.
                existingByName.remove(matchKey(target.getName()));
            } else {
                target = existingByName.remove(name);
            }
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

        rejectNameSwaps(incoming, originalNames, Script::getId,
                s -> matchKey(s.getName()), "Script name");

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
        List<Binding> incoming = new ArrayList<>();
        for (BindingPayloadDto b : dto.getBindings()) {
            Binding target = b.getId() == null ? null : existingById.get(b.getId());
            if (target == null && b.getId() != null) {
                throw new IllegalStateException(
                        "Binding " + b.getId() + " does not belong to component " + entity.getId());
            }
            if (target == null) {
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
    private void applyEvents(Component entity, ComponentCreateDto dto) {
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

        List<ComponentEvent> incoming = new ArrayList<>();
        Set<String> seenTypes = new HashSet<>();
        Set<Long> keptIds = new HashSet<>();
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

            ComponentEvent target = e.getId() == null ? null : existingById.get(e.getId());
            if (target == null && e.getId() != null) {
                throw new IllegalStateException(
                        "Event " + e.getId() + " does not belong to component " + entity.getId());
            }
            if (target != null) {
                // Тот же захват ключа, что в applyScripts: забранная по id строка не должна
                // оставаться доступной под своим прежним типом.
                existingByType.remove(target.getEventType());
            } else {
                target = existingByType.remove(e.getEvent_type());
            }
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

        rejectNameSwaps(incoming, originalTypes, ComponentEvent::getId,
                ComponentEvent::getEventType, "Event type");

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
