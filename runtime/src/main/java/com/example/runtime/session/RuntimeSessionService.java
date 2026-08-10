package com.example.runtime.session;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.dto.TagSnapshot;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.stream.PropertyUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class RuntimeSessionService {

    private final EditorClient editorClient;
    private final RuntimeSessionStore sessionStore;
    private final TagValueRouter tagValueRouter;
    private final ScriptEngineService scriptEngineService;
    private final TagCommandService tagCommandService;

    public RuntimeSessionService(EditorClient editorClient,
                                  RuntimeSessionStore sessionStore,
                                  TagValueRouter tagValueRouter,
                                  ScriptEngineService scriptEngineService,
                                  TagCommandService tagCommandService) {
        this.editorClient = editorClient;
        this.sessionStore = sessionStore;
        this.tagValueRouter = tagValueRouter;
        this.scriptEngineService = scriptEngineService;
        this.tagCommandService = tagCommandService;
    }

    /**
     * Разовое обращение к editor — только здесь, при старте сессии. Это не горячий путь:
     * происходит один раз на сессию, а не на каждое обновление тега. Дерево уже приходит
     * со связями: ComponentProperty.tagId — это путь узла базы каналов, он же Kafka-key,
     * поэтому резолвить его где-то ещё не нужно.
     */
    public SessionBootstrap createSession(Long projectId) {
        EditorComponentDto tree = editorClient.getProjectTree(projectId);
        if (tree == null) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        TagSubscriptionIndex index = TagSubscriptionIndex.build(tree);

        String sessionId = UUID.randomUUID().toString();
        RuntimeSession session = new RuntimeSession(sessionId, projectId, index);
        sessionStore.put(session);
        tagValueRouter.registerSession(session);

        log.info("Runtime session {} started for project {} ({} tags)",
                sessionId, projectId, index.getAllTagIds().size());

        return new SessionBootstrap(session, tree);
    }

    public void closeSession(String sessionId) {
        RuntimeSession session = sessionStore.remove(sessionId);
        if (session == null) {
            return;
        }
        tagValueRouter.unregisterSession(session);
        log.info("Runtime session {} closed", sessionId);
    }

    public RuntimeSession getSession(String sessionId) {
        return sessionStore.get(sessionId);
    }

    /**
     * Выполняет Script компонента по действию с фронта (например, нажатие кнопки).
     * Возвращает список изменившихся свойств — вызывающий (WS-хендлер) сразу шлёт их
     * фронту, не дожидаясь батч-флаша, так как это редкое дискретное событие.
     */
    public List<PropertyUpdate> handleAction(String sessionId, Long scriptId) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            log.warn("ACTION for unknown session {}", sessionId);
            return List.of();
        }
        ScriptEntry script = session.getIndex().getScript(scriptId);
        if (script == null) {
            log.warn("ACTION references unknown script {} in session {}", scriptId, sessionId);
            return List.of();
        }

        List<Long> propertyIds = session.getIndex().propertyIdsOfComponent(script.componentId());
        // HashMap, а не ConcurrentHashMap: свойство может быть не задано (null), а скрипт
        // вправе присвоить props.x = null. Карта короткоживущая и однопоточная (одно
        // выполнение скрипта), поэтому потокобезопасность не нужна.
        Map<String, Object> props = new HashMap<>();
        for (Long propertyId : propertyIds) {
            String name = session.getIndex().propertyName(propertyId);
            if (name != null) {
                props.put(name, session.getPropertyValues().get(propertyId));
            }
        }
        Map<String, Object> before = new HashMap<>(props);

        Map<String, Object> after;
        try {
            after = scriptEngineService.runAction(
                    script.source(), props, tagCommandService.sinkFor(session, script.componentId()));
        } catch (Exception e) {
            log.warn("Script {} execution failed for session {}: {}", scriptId, sessionId, e.getMessage());
            return List.of();
        }

        long ts = System.currentTimeMillis();
        List<PropertyUpdate> changed = new ArrayList<>();
        for (Long propertyId : propertyIds) {
            String name = session.getIndex().propertyName(propertyId);
            if (name == null) {
                continue;
            }
            Object newValue = after.get(name);
            if (!Objects.equals(before.get(name), newValue)) {
                storePropertyValue(session, propertyId, newValue);
                changed.add(new PropertyUpdate(propertyId, name, newValue, ts));
            }
        }
        return changed;
    }

    /**
     * Снимок текущих значений тегов строк таблицы (по требованию, перед загрузкой рецепта —
     * без постоянного потока). Значения берутся из кэша последних значений {@link TagValueRouter};
     * тег без пришедшей телеметрии вернёт {@code value = null}.
     */
    public List<TagSnapshot> snapshot(String sessionId, Long componentId) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session: " + sessionId);
        }
        TagSubscriptionIndex index = session.getIndex();
        List<TagSnapshot> result = new ArrayList<>();
        for (Long propertyId : index.propertyIdsOfComponent(componentId)) {
            String tagId = index.tagIdOfProperty(propertyId);
            if (tagId == null) {
                continue;
            }
            result.add(new TagSnapshot(tagId, tagValueRouter.lastValue(tagId)));
        }
        return result;
    }

    /**
     * Применяет значение к строке <b>без тега</b>: писать в ПЛК нечего, поэтому значение
     * ложится в состояние свойства в сессии и сразу уходит фронту.
     * <p>
     * Свойство ищется по имени, а не по id: сессия строит индекс один раз при старте и живёт с
     * ним часами, а строку в editor за это время могут удалить и завести заново. Пришедший
     * оттуда новый id в старом индексе не нашёлся бы, и запись молча промахнулась бы мимо строки.
     * (Раньше промах был не краевым случаем, а нормой: пересохранение таблицы пересоздавало id
     * всех строк разом — с 07.08.2026 они сопоставляются по имени и стабильны.)
     *
     * @return {@code false}, если сессии нет или в её компоненте нет такой строки
     */
    public boolean applyLocalProperty(String sessionId, Long componentId, String propertyName, Object value) {
        RuntimeSession session = sessionStore.get(sessionId);
        if (session == null) {
            log.warn("Cannot apply local value for '{}': unknown session {}", propertyName, sessionId);
            return false;
        }
        Long propertyId = session.getIndex().propertyIdOfComponentProperty(componentId, propertyName);
        if (propertyId == null) {
            log.warn("Cannot apply local value: component {} has no property '{}' in session {}",
                    componentId, propertyName, sessionId);
            return false;
        }
        storePropertyValue(session, propertyId, value);
        session.getOutboundBuffer().offerProperty(
                new PropertyUpdate(propertyId, propertyName, value, System.currentTimeMillis()));
        return true;
    }

    /**
     * Записывает значение свойства в потокобезопасное хранилище сессии. {@code null}
     * (свойство сброшено) представляется отсутствием ключа — {@code ConcurrentHashMap}
     * не хранит null, а «нет ключа» и трактуется как «значение не задано».
     */
    private static void storePropertyValue(RuntimeSession session, Long propertyId, Object value) {
        if (value == null) {
            session.getPropertyValues().remove(propertyId);
        } else {
            session.getPropertyValues().put(propertyId, value);
        }
    }

    public record SessionBootstrap(RuntimeSession session, EditorComponentDto projectTree) {
    }
}
