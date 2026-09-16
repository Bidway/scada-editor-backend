package com.example.runtime.project;

import com.example.runtime.session.RuntimeSession;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.scriptcore.ProjectData;
import lombok.Getter;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Поднятый проект: живёт, пока стоит флаг «в эксплуатации», и не зависит от того, открыт ли
 * у кого-то монитор. Держит индекс тегов, данные проекта, общее состояние свойств и
 * наблюдателей.
 * <p>
 * {@code propertyValues} общая на проект, а не на сессию: её пишут серверные скрипты
 * ({@code on_change} по смене тега и {@code ACTION} по действию оператора), а это состояние
 * объекта, а не экрана. Пока карта была сессионной, два оператора на одном проекте видели
 * разные значения и их копии никогда не сходились обратно.
 */
@Getter
public class ProjectRuntime {

    private final Long projectId;
    private final TagSubscriptionIndex index;
    private final ProjectData projectData;
    /** Драйверы проекта — первые сегменты путей его тегов. Единица владения. */
    private final Set<String> drivers;
    private final Map<Long, Object> propertyValues;

    private final Map<String, RuntimeSession> observers = new ConcurrentHashMap<>();

    public ProjectRuntime(Long projectId, TagSubscriptionIndex index, ProjectData projectData) {
        this.projectId = projectId;
        this.index = index;
        this.projectData = projectData;
        this.drivers = DriverNames.of(index.getAllTagIds());
        this.propertyValues = new ConcurrentHashMap<>(index.getInitialPropertyValues());
    }

    public void addObserver(RuntimeSession session) {
        observers.put(session.getId(), session);
    }

    public void removeObserver(String sessionId) {
        observers.remove(sessionId);
    }

    /** Наблюдатели: те, кому есть куда слать кадры. Их может не быть вовсе — проект работает. */
    public Collection<RuntimeSession> sessions() {
        return observers.values();
    }
}
