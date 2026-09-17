package com.example.runtime.session;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.dto.TagSnapshot;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ActionDedupGuard;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.stream.PropertyUpdate;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeStore;
import com.example.runtime.recipe.ProjectNotInOperationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class RuntimeSessionService {

    private final RuntimeSessionStore sessionStore;
    private final TagValueRouter tagValueRouter;
    private final ScriptEngineService scriptEngineService;
    private final TagCommandService tagCommandService;
    private final ActionDedupGuard actionDedupGuard;
    private final ProjectRuntimeStore projectStore;

    public RuntimeSessionService(RuntimeSessionStore sessionStore,
                                  ProjectRuntimeStore projectStore,
                                  TagValueRouter tagValueRouter,
                                  ScriptEngineService scriptEngineService,
                                  TagCommandService tagCommandService,
                                  ActionDedupGuard actionDedupGuard) {
        this.sessionStore = sessionStore;
        this.projectStore = projectStore;
        this.tagValueRouter = tagValueRouter;
        this.scriptEngineService = scriptEngineService;
        this.tagCommandService = tagCommandService;
        this.actionDedupGuard = actionDedupGuard;
    }

    /**
     * Сессия ничего не поднимает: проект уже работает по флагу «в эксплуатации», и сессия лишь
     * берёт его дерево для экрана. В наблюдатели она попадает только при подключении WebSocket
     * (см. {@code RuntimeWebSocketHandler}) — до этого слать кадры некуда, и копить их в буфере
     * сессии, которая может так и не подключиться, незачем.
     */
    public SessionBootstrap createSession(Long projectId) {
        ProjectRuntime project = projectStore.get(projectId);
        if (project == null) {
            // Не «пустой экран без объяснения»: выключенный проект должен быть видимым состоянием.
            throw new ProjectNotInOperationException(projectId);
        }

        String sessionId = UUID.randomUUID().toString();
        RuntimeSession session = new RuntimeSession(sessionId, project);
        sessionStore.put(session);

        log.info("Runtime session {} started for project {} ({} tags)",
                sessionId, projectId, project.getIndex().getAllTagIds().size());

        return new SessionBootstrap(session, project.getTree());
    }

    public void closeSession(String sessionId) {
        RuntimeSession session = sessionStore.remove(sessionId);
        if (session == null) {
            return;
        }
        // Сессия — наблюдатель: её уход снимает только подписку на кадры. Интерес к тегам
        // и всё состояние остаются у проекта, поэтому мойка продолжает идти.
        session.getProject().removeObserver(sessionId);
        log.info("Runtime session {} closed", sessionId);
    }

    /** Причина закрытия WS у мониторов выключенного проекта — фронт покажет её оператору. */
    public static final String PROJECT_DEACTIVATED_REASON = "Проект выведен из эксплуатации";

    /**
     * Закрывает все сессии проекта — и подключённые, и созданные по REST, но ещё без WebSocket.
     * Сессия держит ссылку на объект проекта, а повторное включение создаёт новый объект: без
     * закрытия монитор оставался подключён к мёртвому объекту и молча замирал — ни значений, ни
     * признака, что пора переподключиться (scada-m6mh). Закрытое соединение фронт переоткрывает
     * новым POST и получает либо 409, либо сессию на живом объекте.
     */
    public void closeSessionsOf(ProjectRuntime project) {
        for (RuntimeSession session : sessionStore.all()) {
            if (session.getProject() != project) {
                continue;
            }
            sessionStore.remove(session.getId());
            project.removeObserver(session.getId());
            WebSocketSession ws = session.getWebSocketSession();
            if (ws != null && ws.isOpen()) {
                try {
                    ws.close(CloseStatus.GOING_AWAY.withReason(PROJECT_DEACTIVATED_REASON));
                } catch (Exception e) {
                    log.warn("Не удалось закрыть WebSocket сессии {}: {}", session.getId(), e.getMessage());
                }
            }
            log.info("Runtime session {} closed: project {} deactivated", session.getId(), project.getProjectId());
        }
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
        if (!actionDedupGuard.allow(sessionId + ":" + scriptId)) {
            log.warn("ACTION {} for session {} dropped as a duplicate (dedup window)", scriptId, sessionId);
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
                props.put(name, session.getProject().getPropertyValues().get(propertyId));
            }
        }
        Map<String, Object> before = new HashMap<>(props);

        Map<String, Object> after;
        try {
            after = scriptEngineService.runAction(script.source(), props,
                    tagCommandService.sinksFor(session.getProject(), script.componentId()), session.getProjectData());
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
            session.getProject().getPropertyValues().remove(propertyId);
        } else {
            session.getProject().getPropertyValues().put(propertyId, value);
        }
    }

    public record SessionBootstrap(RuntimeSession session, EditorComponentDto projectTree) {
    }
}
