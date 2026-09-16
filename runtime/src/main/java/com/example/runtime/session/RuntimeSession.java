package com.example.runtime.session;

import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.stream.SessionOutboundBuffer;
import com.example.scriptcore.ProjectData;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Наблюдатель проекта: открытый экран оператора. Своего состояния, кроме соединения и очереди
 * отправки, не имеет — индекс, данные проекта и значения свойств принадлежат
 * {@link ProjectRuntime} и общие для всех, кто смотрит на этот проект. Пока карта свойств была
 * сессионной, два оператора видели разные значения и их копии не сходились обратно.
 */
public class RuntimeSession {

    private final String id;
    private final ProjectRuntime project;
    private final SessionOutboundBuffer outboundBuffer = new SessionOutboundBuffer();
    private final Instant createdAt = Instant.now();
    private final ReentrantLock sendLock = new ReentrantLock();

    private volatile WebSocketSession webSocketSession;

    /** Прислала ли сессия SUBSCRIBE_TASKS: статусы задач нужны только открытой панели «Задачи». */
    private volatile boolean tasksSubscribed;

    public RuntimeSession(String id, ProjectRuntime project) {
        this.id = id;
        this.project = project;
    }

    public String getId() {
        return id;
    }

    public ProjectRuntime getProject() {
        return project;
    }

    public Long getProjectId() {
        return project.getProjectId();
    }

    /** Делегирует проекту: у сессии своего индекса нет. */
    public TagSubscriptionIndex getIndex() {
        return project.getIndex();
    }

    /** Делегирует проекту: таблицы данных общие, как и всё остальное состояние проекта. */
    public ProjectData getProjectData() {
        return project.getProjectData();
    }

    public SessionOutboundBuffer getOutboundBuffer() {
        return outboundBuffer;
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
