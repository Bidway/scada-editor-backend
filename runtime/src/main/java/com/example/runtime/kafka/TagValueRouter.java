package com.example.runtime.kafka;

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
    private final ObjectMapper objectMapper;

    /** Ключ = tagId = Kafka-key. Запись удаляется, когда уходит последняя сессия. */
    private final Map<String, TagRuntimeState> tagStates = new ConcurrentHashMap<>();

    public TagValueRouter(RuntimeSessionStore sessionStore,
                          ScriptEngineService scriptEngineService,
                          TagCommandService tagCommandService,
                          ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.scriptEngineService = scriptEngineService;
        this.tagCommandService = tagCommandService;
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

            String lastValue = state.lastValue;
            if (lastValue != null) {
                session.getOutboundBuffer().offerTag(new TagUpdate(tagId, lastValue, System.currentTimeMillis()));
            }
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

    /** Последнее известное значение тега (для снимка по требованию перед загрузкой рецепта). */
    public String lastValue(String tagId) {
        TagRuntimeState state = tagStates.get(tagId);
        return state != null ? state.lastValue : null;
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
        String value = extractValue(event.rawValue(), tagId);
        state.lastValue = value;

        long ts = System.currentTimeMillis();
        for (String sessionId : state.sessionIds) {
            dispatchToSession(sessionId, tagId, value, ts);
        }
    }

    private void dispatchToSession(String sessionId, String tagId, String value, long ts) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            return;
        }
        session.getOutboundBuffer().offerTag(new TagUpdate(tagId, value, ts));

        List<OnChangeBinding> onChangeBindings = session.getIndex().onChangeBindingsForTag(tagId);
        if (onChangeBindings.isEmpty()) {
            return;
        }
        Object coercedValue = coerce(value);
        for (OnChangeBinding binding : onChangeBindings) {
            runOnChangeAndPublish(session, binding, coercedValue, ts);
        }
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
     * Достаёт значение тега из сообщения. В топике сосуществуют два формата:
     * драйвер устройства шлёт JSON-конверт (значение в поле {@code value}), а ручные
     * публикации — голый скаляр. Конверт распаковывается, всё остальное отдаётся как
     * есть, поэтому дальше по цепочке значение всегда остаётся сырой строкой
     * ({@code "72.7"}, {@code "true"}) — модель тега целиком строковая.
     * <p>
     * Вызывается только для подписанных тегов, поэтому и разбор, и предупреждение о
     * битом теле ограничены тем, что реально смотрят операторы: залить лог потоком в
     * миллион сообщений здесь нечем.
     */
    private String extractValue(String raw, String tagId) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '{') {
            return raw;
        }
        try {
            JsonNode value = objectMapper.readTree(raw).get("value");
            if (value == null) {
                return raw;
            }
            return value.isNull() ? null : value.asText();
        } catch (Exception e) {
            // Раньше битый конверт молча уезжал оператору на экран как значение тега.
            log.warn("Tag '{}': malformed message envelope, using raw payload: {}", tagId, e.getMessage());
            return raw;
        }
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
