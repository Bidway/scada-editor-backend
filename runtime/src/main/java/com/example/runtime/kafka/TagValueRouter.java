package com.example.runtime.kafka;

import com.example.runtime.script.OnChangeDispatcher;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.session.OnChangeBinding;
import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.RuntimeSessionStore;
import com.example.runtime.session.TagCommandService;
import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.stream.TagUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    /** Ключ = tagId = Kafka-key. Запись удаляется, когда уходит последняя сессия. */
    private final Map<String, TagRuntimeState> tagStates = new ConcurrentHashMap<>();

    public TagValueRouter(RuntimeSessionStore sessionStore,
                          ScriptEngineService scriptEngineService,
                          TagCommandService tagCommandService,
                          OnChangeDispatcher onChangeDispatcher,
                          ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.scriptEngineService = scriptEngineService;
        this.tagCommandService = tagCommandService;
        this.onChangeDispatcher = onChangeDispatcher;
        this.objectMapper = objectMapper;
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
        Envelope envelope = parse(event.rawValue(), tagId);

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
        Object coercedValue = coerce(snapshot.value());
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
                    binding.scriptSource(), tagValue, props, tagCommandService.sinkFor(session, componentId));
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

    /**
     * Строгий формат числа: опциональный знак, целая часть без ведущих нулей
     * ({@code 0} либо {@code [1-9]\d*}), опциональная дробная часть и экспонента.
     * Намеренно отвергает то, что {@link Double#parseDouble} проглотил бы неверно:
     * {@code NaN}/{@code Infinity}, суффиксы типа {@code "1d"}/{@code "1f"}, hex-литералы,
     * и статус-коды с ведущими нулями ({@code "0012"} должен остаться строкой, а не стать 12.0).
     */
    private static final Pattern NUMERIC =
            Pattern.compile("[+-]?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * Разобранное тело сообщения телеметрии.
     *
     * @param value    значение тега сырой строкой ({@code "72.7"}, {@code "true"}) —
     *                 модель тега по всей цепочке строковая
     * @param good     достоверно ли значение
     * @param sourceTs момент снятия значения с контроллера (epoch ms), {@code null} —
     *                 источник времени не прислан, берём момент приёма
     */
    private record Envelope(String value, boolean good, Long sourceTs) {

        static Envelope raw(String value) {
            return new Envelope(value, true, null);
        }
    }

    /**
     * Достаёт значение, качество и метку времени из сообщения. В топике сосуществуют
     * три формата, и все три разбираются одним кодом:
     * <ul>
     *   <li>текущий контракт шлюза — {@code {value, quality, timestamp}};</li>
     *   <li>прежний 13-польный {@code TelemetryMessage} — берётся {@code value},
     *       качества и времени в нём фактически не было;</li>
     *   <li>голый скаляр от ручных публикаций и {@code kafka-sim}.</li>
     * </ul>
     * Совместимость держится на том, что отсутствующее {@code quality} трактуется как
     * достоверное: иначе выкладка шлюза и runtime стали бы связаны по порядку, а старый
     * формат мгновенно погасил бы все теги на экранах.
     * <p>
     * Вызывается только для подписанных тегов, поэтому и разбор, и предупреждение о
     * битом теле ограничены тем, что реально смотрят операторы: залить лог потоком в
     * миллион сообщений здесь нечем.
     */
    private Envelope parse(String raw, String tagId) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '{') {
            return Envelope.raw(raw);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode value = root.get("value");
            if (value == null) {
                return Envelope.raw(raw);
            }
            return new Envelope(
                    value.isNull() ? null : value.asText(),
                    isGood(root.get("quality")),
                    sourceTs(root.get("timestamp")));
        } catch (Exception e) {
            // Раньше битый конверт молча уезжал оператору на экран как значение тега.
            log.warn("Tag '{}': malformed message envelope, using raw payload: {}", tagId, e.getMessage());
            return Envelope.raw(raw);
        }
    }

    /**
     * Достоверно <b>только</b> {@code "GOOD"}. Проверять на равенство {@code "BAD"}
     * нельзя: контракт расширяемый, и у OPC UA рядом есть {@code UNCERTAIN} — он тоже
     * не является основанием что-то показывать оператору как факт.
     */
    private static boolean isGood(JsonNode quality) {
        return quality == null || quality.isNull() || TagUpdate.GOOD.equalsIgnoreCase(quality.asText());
    }

    /**
     * Граница «это секунды, а не миллисекунды». {@code 1e11} мс — это 1973 год, а
     * {@code 1e11} с — 5138-й: между осмысленными датами в двух единицах зазор в тысячи
     * лет, поэтому порог надёжен и не требует договорённости с отправителем.
     */
    private static final double EPOCH_SECONDS_CEILING = 1e11;

    /**
     * Метка времени источника, приведённая к epoch ms. Принимаются три написания:
     * <ul>
     *   <li>строка ISO-8601 — то, что описано в контракте;</li>
     *   <li>дробное число — Jackson без {@code JavaTimeModule} сериализует {@code Instant}
     *       как epoch-<b>секунды</b> с наносекундной дробью ({@code 1785935496.271793106});</li>
     *   <li>целое число — epoch ms от простых публикаций.</li>
     * </ul>
     * Второй случай реален: именно так шлюз пишет сегодня. Приняв его за миллисекунды,
     * мы датировали бы все значения январём 1970-го, и «возраст значения» на экране
     * оператора составил бы 56 лет.
     * <p>
     * Разбор не должен ронять приём телеметрии, поэтому любая неудача означает лишь
     * фолбэк на момент приёма; на уровень {@code warn} это не выносится — при ~1200
     * сообщениях в секунду кривой формат залил бы лог целиком.
     */
    private Long sourceTs(JsonNode timestamp) {
        if (timestamp == null || timestamp.isNull()) {
            return null;
        }
        if (timestamp.isNumber()) {
            double raw = timestamp.asDouble();
            return raw < EPOCH_SECONDS_CEILING ? Math.round(raw * 1000) : (long) raw;
        }
        try {
            return Instant.parse(timestamp.asText()).toEpochMilli();
        } catch (Exception e) {
            log.debug("Unparseable telemetry timestamp '{}', falling back to receive time", timestamp.asText());
            return null;
        }
    }

    private static TagUpdate toUpdate(String tagId, TagRuntimeState.Snapshot snapshot) {
        return new TagUpdate(tagId, snapshot.value(), snapshot.ts(),
                snapshot.good() ? TagUpdate.GOOD : TagUpdate.BAD);
    }

    private Object coerce(String value) {
        if (value == null) {
            return null;
        }
        // Дискретные теги (OPC UA) приходят как true/false — без этого скрипт получал бы строку.
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        if (NUMERIC.matcher(value).matches()) {
            double d = Double.parseDouble(value);
            // Отсекаем переполнение (например "1e400" -> Infinity): Jackson такое не сериализует.
            if (Double.isFinite(d)) {
                return d;
            }
        }
        return value;
    }
}
