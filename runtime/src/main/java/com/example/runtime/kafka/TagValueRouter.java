package com.example.runtime.kafka;

import com.example.runtime.script.OnChangeDispatcher;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.OnChangeBinding;
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
    private final ScriptEngineService scriptEngineService;
    private final TagCommandService tagCommandService;
    private final OnChangeDispatcher onChangeDispatcher;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** Ключ = tagId = Kafka-key. Запись удаляется, когда уходит последняя сессия. */
    private final Map<String, TagRuntimeState> tagStates = new ConcurrentHashMap<>();

    public TagValueRouter(RuntimeSessionStore sessionStore,
                          ScriptEngineService scriptEngineService,
                          TagCommandService tagCommandService,
                          OnChangeDispatcher onChangeDispatcher,
                          ObjectMapper objectMapper,
                          ApplicationEventPublisher eventPublisher) {
        this.sessionStore = sessionStore;
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
    public void registerSession(RuntimeSession session) {
        for (String tagId : session.getIndex().getAllTagIds()) {
            // compute атомарен на ключ — иначе одновременные register/unregister
            // могут выбросить состояние ещё живой сессии.
            TagRuntimeState state = tagStates.compute(tagId, (key, existing) -> {
                TagRuntimeState s = existing != null ? existing : new TagRuntimeState(key);
                s.sessionIds.add(session.getId());
                return s;
            });

            // Кадр отдаётся всегда, даже когда значения ещё не было: тег с value=null и
            // quality=BAD — это штатное «нет данных», а не пустой экран без объяснения.
            // Холодный старт реального шлюза длится до полутора минут (последовательный
            // обход 2471 канала при auto.offset.reset=latest), и всё это время оператор
            // должен видеть, что данных нет, а не гадать.
            session.getOutboundBuffer().offerTag(toUpdate(tagId, state.snapshot));
        }
    }

    public void unregisterSession(RuntimeSession session) {
        for (String tagId : session.getIndex().getAllTagIds()) {
            tagStates.computeIfPresent(tagId, (key, state) -> {
                state.sessionIds.remove(session.getId());
                return state.sessionIds.isEmpty() ? null : state;
            });
        }
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

        for (String sessionId : state.sessionIds) {
            dispatchToSession(sessionId, tagId, snapshot, valueChanged);
        }
    }

    private void dispatchToSession(String sessionId, String tagId,
                                   TagRuntimeState.Snapshot snapshot, boolean valueChanged) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            return;
        }
        // Лёгкая часть остаётся на треде consumer'а: запись в буфер — это добавление в
        // очередь, доли микросекунды, и оно должно происходить как можно ближе к моменту
        // приёма, чтобы значение на экране было свежим.
        session.getOutboundBuffer().offerTag(toUpdate(tagId, snapshot));
        eventPublisher.publishEvent(new SessionTagChangedEvent(sessionId));

        // Недостоверное значение до скриптов не доходит вообще. Значение тега не
        // «изменилось» — оно стало неизвестным, а это не событие процесса, на которое
        // скрипт должен реагировать. Пропусти мы null внутрь, типичный биндинг
        // setState(tag ? 'Открыт' : 'Закрыт') при потере связи уверенно нарисовал бы
        // клапан ЗАКРЫТЫМ: null в JS — falsy. Замерший тег плохо, тег с уверенно
        // неверным состоянием — хуже. Компонент остаётся как есть, а «нет данных»
        // рисует фронт по quality из кадра.
        if (!snapshot.good() || !valueChanged) {
            return;
        }

        List<OnChangeBinding> onChangeBindings = session.getIndex().onChangeBindingsForTag(tagId);
        if (onChangeBindings.isEmpty()) {
            return;
        }
        // Свойству — время его вычисления, а не метка измерения из snapshot.ts(). Это
        // разные величины: тег датируется моментом снятия с ПЛК (и потому не монотонен —
        // часы контроллера свои), а свойство порождается скриптом здесь и сейчас.
        // Смешав их, мы протащили бы дрейф часов ПЛК в properties[], про который фронту
        // не сказано ни слова.
        long ts = System.currentTimeMillis();
        Object coercedValue = coerceTagValue(snapshot.value());
        // Тяжёлая часть уходит в пул: GraalVM с таймаутом до 200 мс на треде consumer'а
        // останавливал бы приём телеметрии для всех сессий разом.
        onChangeDispatcher.submit(sessionId, () -> {
            for (OnChangeBinding binding : onChangeBindings) {
                runOnChangeAndPublish(session, binding, coercedValue, ts);
            }
        });
    }

    private void runOnChangeAndPublish(RuntimeSession session, OnChangeBinding binding, Object tagValue, long ts) {
        Long componentId = binding.componentId();
        List<Long> propertyIds = session.getIndex().propertyIdsOfComponent(componentId);
        // HashMap, а не ConcurrentHashMap: значение свойства может быть не задано (null),
        // и скрипт вправе выставить props.x = null. Карта живёт одно выполнение скрипта.
        Map<String, Object> props = new HashMap<>();
        for (Long propertyId : propertyIds) {
            String name = session.getIndex().propertyName(propertyId);
            Object current = session.getPropertyValues().get(propertyId);
            if (name != null) {
                props.put(name, current);
            }
        }
        Map<String, Object> before = new HashMap<>(props);

        Map<String, Object> after;
        try {
            after = scriptEngineService.runOnChange(
                    binding.scriptSource(), tagValue, props, tagCommandService.sinksFor(session, componentId));
        } catch (Exception e) {
            log.warn("onChange script failed for property {}: {}", binding.componentPropertyId(), e.getMessage());
            return;
        }

        for (Long propertyId : propertyIds) {
            String name = session.getIndex().propertyName(propertyId);
            if (name == null) {
                continue;
            }
            Object newValue = after.get(name);
            if (!java.util.Objects.equals(before.get(name), newValue)) {
                storePropertyValue(session, propertyId, newValue);
                        session.getOutboundBuffer().offerProperty(new PropertyUpdate(propertyId, name, newValue, ts));
            }
        }
    }

    /**
     * Записывает значение свойства в хранилище сессии. {@code null} (свойство сброшено
     * скриптом) представляется отсутствием ключа — {@code ConcurrentHashMap} не хранит null.
     */
    private static void storePropertyValue(RuntimeSession session, Long propertyId, Object value) {
        if (value == null) {
            session.getPropertyValues().remove(propertyId);
        } else {
            session.getPropertyValues().put(propertyId, value);
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
