package com.example.runtime.session;

import com.example.runtime.stream.SessionOutboundBuffer;
import com.example.scriptcore.ProjectData;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class RuntimeSession {

    private final String id;
    private final Long projectId;
    private final TagSubscriptionIndex index;
    private final ProjectData projectData;
    private final SessionOutboundBuffer outboundBuffer = new SessionOutboundBuffer();
    private final Map<Long, Object> propertyValues;
    private final Instant createdAt = Instant.now();
    private final ReentrantLock sendLock = new ReentrantLock();

    private volatile WebSocketSession webSocketSession;

    /** Прислала ли сессия SUBSCRIBE_TASKS: статусы задач нужны только открытой панели «Задачи». */
    private volatile boolean tasksSubscribed;

    public RuntimeSession(String id, Long projectId, TagSubscriptionIndex index) {
        this(id, projectId, index, ProjectData.EMPTY);
    }

    public RuntimeSession(String id, Long projectId, TagSubscriptionIndex index, ProjectData projectData) {
        this.id = id;
        this.projectId = projectId;
        this.index = index;
        this.projectData = projectData;
        this.propertyValues = new ConcurrentHashMap<>(index.getInitialPropertyValues());
    }

    public String getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public TagSubscriptionIndex getIndex() {
        return index;
    }

    /** Снимок таблиц данных проекта, взятый при открытии сессии; правка в редакторе видна новым сессиям. */
    public ProjectData getProjectData() {
        return projectData;
    }

    public SessionOutboundBuffer getOutboundBuffer() {
        return outboundBuffer;
    }

    public Map<Long, Object> getPropertyValues() {
        return propertyValues;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }

    public void setWebSocketSession(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    /** WebSocketSession.sendMessage не потокобезопасен при конкурентных отправках. */
    public ReentrantLock getSendLock() {
        return sendLock;
    }

    public boolean isTasksSubscribed() {
        return tasksSubscribed;
    }

    public void setTasksSubscribed(boolean tasksSubscribed) {
        this.tasksSubscribed = tasksSubscribed;
    }
}
