package com.example.runtime.project;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр поднятых проектов. Живёт столько же, сколько сервис: проект попадает сюда по флагу
 * «в эксплуатации» и уходит только при его снятии — не при уходе оператора.
 */
@Component
public class ProjectRuntimeStore {

    private final Map<Long, ProjectRuntime> projects = new ConcurrentHashMap<>();

    public void put(ProjectRuntime project) {
        projects.put(project.getProjectId(), project);
    }

    public ProjectRuntime get(Long projectId) {
        return projects.get(projectId);
    }

    public ProjectRuntime remove(Long projectId) {
        return projects.remove(projectId);
    }

    public Collection<ProjectRuntime> all() {
        return projects.values();
    }
}
