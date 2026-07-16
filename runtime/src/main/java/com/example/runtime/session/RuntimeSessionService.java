package com.example.runtime.session;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.kafka.TagValueRouter;
import com.example.runtime.script.ScriptEngineService;
import com.example.runtime.stream.PropertyUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class RuntimeSessionService {

    private final EditorClient editorClient;
    private final RuntimeSessionStore sessionStore;
    private final TagValueRouter tagValueRouter;
    private final ScriptEngineService scriptEngineService;

    public RuntimeSessionService(EditorClient editorClient,
                                  RuntimeSessionStore sessionStore,
                                  TagValueRouter tagValueRouter,
                                  ScriptEngineService scriptEngineService) {
        this.editorClient = editorClient;
        this.sessionStore = sessionStore;
        this.tagValueRouter = tagValueRouter;
        this.scriptEngineService = scriptEngineService;
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
        Map<String, Object> props = new ConcurrentHashMap<>();
        for (Long propertyId : propertyIds) {
            String name = session.getIndex().propertyName(propertyId);
            if (name != null) {
                props.put(name, session.getPropertyValues().get(propertyId));
            }
        }
        Map<String, Object> before = Map.copyOf(props);

        Map<String, Object> after;
        try {
            after = scriptEngineService.runAction(script.source(), props);
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
                session.getPropertyValues().put(propertyId, newValue);
                changed.add(new PropertyUpdate(propertyId, name, newValue, ts));
            }
        }
        return changed;
    }

    public record SessionBootstrap(RuntimeSession session, EditorComponentDto projectTree) {
    }
}
