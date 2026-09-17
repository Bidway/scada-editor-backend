package com.example.runtime.assignment;

import com.example.runtime.instance.InstanceIdentity;
import com.example.runtime.instance.InstanceRegistrar;
import com.example.runtime.kafka.TopicConnections;
import com.example.runtime.project.ProjectRuntime;
import com.example.runtime.project.ProjectRuntimeService;
import com.example.runtime.project.ProjectRuntimeStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Раз в 5 с перечитывает свои назначения и применяет разницу. Ничего не распределяет сам: делает ровно
 * то, что записано. База недоступна — текущие назначения остаются как есть, ничего не гасится.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssignmentApplier {

    private final InstanceIdentity identity;
    private final InstanceRegistrar registrar;
    private final InstanceTopicRepository topics;
    private final InstanceTopicPrefixRepository prefixes;
    private final InstanceProjectRepository projects;
    private final AssignmentState state;
    private final TopicConnections connections;
    private final ProjectRuntimeService projectRuntimeService;
    private final ProjectRuntimeStore projectStore;

    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready = true;
        apply();
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduled() {
        if (ready) {
            apply();
        }
    }

    synchronized void apply() {
        List<AssignmentState.TopicAssignment> ownTopics;
        Set<Long> ownProjects;
        try {
            ownTopics = topics.findByInstanceId(identity.instanceId()).stream()
                    .map(t -> new AssignmentState.TopicAssignment(t.getTelemetryTopic(), t.getCommandsTopic(),
                            t.getResultsTopic(), prefixes.findByTelemetryTopic(t.getTelemetryTopic()).stream()
                            .map(InstanceTopicPrefixEntity::getPathPrefix).toList()))
                    .toList();
            ownProjects = new HashSet<>();
            projects.findByInstanceId(identity.instanceId()).forEach(p -> ownProjects.add(p.getProjectId()));
            registrar.markSeen();
        } catch (Exception e) {
            log.warn("Назначения не перечитаны, работаю с прежними: {}", e.getMessage());
            return;
        }

        Set<Long> before = state.projects();
        state.update(ownTopics, ownProjects);
        connections.sync(ownTopics);

        for (Long projectId : ownProjects) {
            if (!before.contains(projectId)) {
                // Поднимется, только если стоит флаг «в эксплуатации» (сверка с editor внутри activate).
                projectRuntimeService.activate(projectId);
            }
        }
        for (ProjectRuntime running : List.copyOf(projectStore.all())) {
            if (!ownProjects.contains(running.getProjectId())) {
                log.info("Проект {} снят с экземпляра {} — гашу", running.getProjectId(), identity.instanceId());
                projectRuntimeService.deactivate(running.getProjectId());
            }
        }
    }
}
