package com.example.runtime.kafka;

import com.example.runtime.script.OnChangeDispatcher;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.OnChangeBinding;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagCommandService;
import com.example.runtime.session.VariableTags;
import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.stream.TagUpdate;
import com.example.scriptcore.TelemetryEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Горячий путь: диспетчеризация сообщений единого Kafka-топика проекта на все сессии,
 * которым интересен соответствующий тег, плюс запуск onChange-скриптов там, где они
 * привязаны. Сообщения приходят из Kafka напрямую в runtime — без обращений к
 * channel/editor.
 * <p>
 * Маршрутизация — по key сообщения, который равен {@code ComponentProperty.tagId}
 * (путь узла базы каналов, например {@code Барановичи-1.BN1_MCA1.AI_M.AI2.M}).
 * Дерево проекта приходит от editor уже со связями, поэтому ключ известен сразу и
 * резолвить его нигде не нужно.
 */
@Component
@Slf4j
public class TagValueRouter {

    private final RuntimeSessionStore sessionStore;
    private final ProjectRuntimeStore projectStore;
    private final ScriptEngineService scriptEngineService;
    private final TagCommandService tagCommandService;
    private final OnChangeDispatcher onChangeDispatcher;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** Ключ = tagId = Kafka-key. Запись удаляется, когда уходит последний проект. */
    private final Map<String, TagRuntimeState> tagStates = new ConcurrentHashMap<>();

    public TagValueRouter(RuntimeSessionStore sessionStore,
                          ProjectRuntimeStore projectStore,
                          ScriptEngineService scriptEngineService,
                          TagCommandService tagCommandService,
                          OnChangeDispatcher onChangeDispatcher,
                          ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.sessionStore = sessionStore;
        this.projectStore = projectStore;
        this.scriptEngineService = scriptEngineService;
        this.tagCommandService = tagCommandService;
        this.onChangeDispatcher = onChangeDispatcher;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Регистрирует интерес сессии ко всем её тегам. Если значение тега уже известно
     * (его успела принести другая сессия), оно сразу отдаётся новой сессии, чтобы та
     * не ждала следующего обновления.
     */
    /**
     * Интерес к тегам объявляет проект, а не сессия: значения должны жить, пока проект в
     * эксплуатации, даже когда на него никто не смотрит. Пока записи в tagStates создавались
     * регистрацией сессии и исчезали с последней, условия процедуры после ухода оператора
     * получали из readProjectTag только null и мойка вставала навсегда.
     */
    public void registerProject(ProjectRuntime project) {
        for (String tagId : project.getIndex().getAllTagIds()) {
            // compute атомарен на ключ — иначе одновременные register/unregister могут
            // выбросить состояние ещё живого проекта.
            tagStates.compute(tagId, (key, existing) -> {
                TagRuntimeState s = existing != null ? existing : new TagRuntimeState(key);
                s.projectIds.add(project.getProjectId());
                return s;
            });
        }
    }

    public void unregisterProject(ProjectRuntime project) {
        for (String tagId : project.getIndex().getAllTagIds()) {
            tagStates.computeIfPresent(tagId, (key, state) -> {
                state.projectIds.remove(project.getProjectId());
                return state.projectIds.isEmpty() ? null : state;
            });
        }
    }

    /**
     * Текущее состояние всех тегов проекта — для кадра, который получает подключившийся
     * наблюдатель. Кадр отдаётся и для тега без значения: тег с value=null и quality=BAD —
     * это штатное «нет данных», а не пустой экран без объяснения. Холодный старт реального
     * шлюза длится до полутора минут, и всё это время оператор должен видеть, что данных нет,
     * а не гадать.
     */
    public List<TagUpdate> snapshot(ProjectRuntime project) {
        List<TagUpdate> updates = new ArrayList<>();
        for (String tagId : project.getIndex().getAllTagIds()) {
            TagRuntimeState state = tagStates.get(tagId);
            updates.add(toUpdate(tagId, state == null ? TagRuntimeState.Snapshot.EMPTY : state.snapshot));
        }
        return updates;
    }

    /**
     * Последнее <b>достоверное</b> значение тега (для снимка по требованию перед загрузкой
     * рецепта). Значение, прочитанное с плохим качеством, сюда не попадает: в набор лучше
     * записать устаревшее, но реально снятое с ПЛК число, чем то, которого там не было.
     */
    public String lastValue(String tagId) {
        TagRuntimeState state = tagStates.get(tagId);
        return state != null ? state.snapshot.value() : null;
    }

    @EventListener
    public void onMessage(KafkaTagMessageEvent event) {
        String tagId = event.key();
        if (tagId == null) {
            return;
        }
        // Проверка подписки идёт ПЕРЕД распаковкой тела: в топике значения всех тегов
        // установки, а подписаны те сотни, что открыты на экранах операторов. Разбор
        // JSON до этой проверки был бы работой впустую для подавляющего большинства
        // сообщений — это самая горячая точка приёма телеметрии.
        TagRuntimeState state = tagStates.get(tagId);
        if (state == null) {
            return;
        }
        TelemetryEnvelope envelope = TelemetryEnvelope.parse(objectMapper, event.rawValue(),
                // Раньше битый конверт молча уезжал оператору на экран как значение тега.
                e -> log.warn("Tag '{}': malformed message envelope, using raw payload: {}", tagId, e.getMessage()));

        // Недостоверное чтение НЕ затирает последнее хорошее значение — оно лишь снимает
        // с него признак актуальности. Иначе обрыв связи стирал бы с мнемосхемы всё, что
        // оператор знал о процессе секунду назад.
        TagRuntimeState.Snapshot previous = state.snapshot;
        TagRuntimeState.Snapshot snapshot = envelope.good()
                ? new TagRuntimeState.Snapshot(
                        envelope.value(),
                        true,
                        envelope.sourceTs() != null ? envelope.sourceTs() : System.currentTimeMillis())
                : previous.asBad();
        state.snapshot = snapshot;

        // onChange — «при изменении», а не «при сообщении». Шлюз шлёт значение каждого
        // тега каждый цикл опроса, меняется оно или нет; без этой проверки каждый
        // подписанный тег заводил бы GraalVM раз в две секунды впустую.
        //
        // Раньше проверки не было, и именно ложное срабатывание служило единственным
        // признаком, что команда доехала: значение возвращалось телеметрией и
        // перерисовывало компонент, даже не изменившись. Теперь исход команды приходит
        // из scada-command-results (см. CommandResultConsumer), и опираться на побочный
        // эффект больше не нужно.
        //
        // Возврат из BAD в GOOD с тем же значением изменением не считается: во время
        // недостоверности скрипты не запускались, поэтому состояние компонента всё это
        // время и отражало это самое значение — пересчитывать нечего.
        boolean valueChanged = !java.util.Objects.equals(previous.value(), snapshot.value());
        boolean qualityChanged = previous.good() != snapshot.good();

        for (Long projectId : state.projectIds) {
            ProjectRuntime project = projectStore.get(projectId);
            if (project == null) {
                continue;
            }
            dispatchToProject(project, tagId, snapshot, valueChanged, qualityChanged);
        }
    }

    private void dispatchToProject(ProjectRuntime project, String tagId,
                                   TagRuntimeState.Snapshot snapshot, boolean valueChanged,
                                   boolean qualityChanged) {
        // Лёгкая часть остаётся на треде consumer'а: запись в буфер — это добавление в
        // очередь, доли микросекунды, и оно должно происходить как можно ближе к моменту
        // приёма, чтобы значение на экране было свежим. Наблюдателей может не быть вовсе —
        // тогда рассылать просто некому, а проект продолжает работать.
        TagUpdate update = toUpdate(tagId, snapshot);
        for (RuntimeSession session : project.sessions()) {
            session.getOutboundBuffer().offerTag(update);
        }
        // Событие проекта публикуется независимо от наблюдателей: условия процедур обязаны
        // пересчитываться и тогда, когда монитор не открыт ни у кого. Но только на изменение:
        // шлюз шлёт каждый тег каждый цикл опроса (~320 сообщений/с на проект стенда), и
        // событие на каждое ставило пересчёт процедур в очередь без предела. Условие, которое
        // не изменилось при тех же значениях, пересчитывать незачем, а условия на времени
        // ловит тик. Смена качества считается изменением: потеря связи — событие процесса.
        if (valueChanged || qualityChanged) {
            eventPublisher.publishEvent(new ProjectTagChangedEvent(project.getProjectId()));
        }

        // Недостоверное значение до скриптов не доходит вообще. Значение тега не
        // «изменилось» — оно стало неизвестным, а это не событие процесса, на которое
        // скрипт должен реагировать. Пропусти мы null внутрь, типичный биндинг
        // setState(tag ? 'Открыт' : 'Закрыт') при потере связи уверенно нарисовал бы
        // клапан ЗАКРЫТЫМ: null в JS — falsy.
        if (!snapshot.good() || !valueChanged) {
            return;
        }

        List<OnChangeBinding> onChangeBindings = project.getIndex().onChangeBindingsForTag(tagId);
        if (onChangeBindings.isEmpty()) {
            return;
        }
        // Свойству — время его вычисления, а не метка измерения из snapshot.ts(). Это
        // разные величины: тег датируется моментом снятия с ПЛК (и потому не монотонен —
        // часы контроллера свои), а свойство порождается скриптом здесь и сейчас.
        long ts = System.currentTimeMillis();
        Object coercedValue = coerceTagValue(snapshot.value());
        // Один раз на проект, а не на каждого наблюдателя: у записи в ПЛК внутри скрипта
        // не должно быть кратности числу открытых экранов.
        onChangeDispatcher.submit(project.getProjectId(), () -> {
            for (OnChangeBinding binding : onChangeBindings) {
                runOnChangeAndPublish(project, binding, coercedValue, ts);
            }
        });
    }

    private void runOnChangeAndPublish(ProjectRuntime project, OnChangeBinding binding, Object tagValue, long ts) {
        Long componentId = binding.componentId();
        List<Long> propertyIds = project.getIndex().propertyIdsOfComponent(componentId);
        // HashMap, а не ConcurrentHashMap: значение свойства может быть не задано (null),
        // и скрипт вправе выставить props.x = null. Карта живёт одно выполнение скрипта.
        Map<String, Object> props = new HashMap<>();
        for (Long propertyId : propertyIds) {
            String name = project.getIndex().propertyName(propertyId);
            Object current = project.getPropertyValues().get(propertyId);
            if (name != null) {
                props.put(name, current);
            }
        }
        Map<String, Object> before = new HashMap<>(props);

        Map<String, Object> after;
        try {
            after = scriptEngineService.runOnChange(binding.scriptSource(), tagValue, props,
                    tagCommandService.sinksFor(project, componentId), project.getProjectData());
        } catch (Exception e) {
            log.warn("onChange script failed for property {}: {}", binding.componentPropertyId(), e.getMessage());
            return;
        }

        for (Long propertyId : propertyIds) {
            String name = project.getIndex().propertyName(propertyId);
            if (name == null) {
                continue;
            }
            Object newValue = after.get(name);
            if (!java.util.Objects.equals(before.get(name), newValue)) {
                storePropertyValue(project, propertyId, newValue);
                PropertyUpdate update = new PropertyUpdate(propertyId, name, newValue, ts);
                // Свойство посчитано один раз, а увидеть его должны все наблюдатели проекта.
                for (RuntimeSession session : project.sessions()) {
                    session.getOutboundBuffer().offerProperty(update);
                }
            }
        }
    }

    /**
     * Записывает значение свойства в общее состояние проекта. {@code null} (свойство сброшено
     * скриптом) представляется отсутствием ключа — {@code ConcurrentHashMap} не хранит null.
     */
    private static void storePropertyValue(ProjectRuntime project, Long propertyId, Object value) {
        if (value == null) {
            project.getPropertyValues().remove(propertyId);
        } else {
            project.getPropertyValues().put(propertyId, value);
        }
    }

    private static TagUpdate toUpdate(String tagId, TagRuntimeState.Snapshot snapshot) {
        return new TagUpdate(VariableTags.displayId(tagId), snapshot.value(), snapshot.ts(),
                snapshot.good() ? TagUpdate.GOOD : TagUpdate.BAD);
    }

    /**
     * Строка тега → примитив JS (Boolean/Double/String) по её виду. Публичный — им же
     * пользуется {@code ProcedureExecutionService} при чтении тега условием шага
     * процедуры ({@code readProjectTag}), не только диспетчинг телеметрии здесь.
     */
    public static Object coerceTagValue(String value) {
        return TelemetryEnvelope.coerce(value);
    }
}
