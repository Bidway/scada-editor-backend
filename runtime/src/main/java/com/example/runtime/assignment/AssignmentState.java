package com.example.runtime.assignment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Свои назначения экземпляра в памяти — снимок последнего перечитывания (≤5 с). По нему поднимаются
 * проекты и выбирается командный топик: горячий путь записи в ПЛК не ходит в базу.
 */
@Component
public class AssignmentState {

    public record TopicAssignment(String telemetryTopic, String commandsTopic, String resultsTopic,
                                  List<String> pathPrefixes) {
    }

    private record Snapshot(List<TopicAssignment> topics, Set<Long> projects) {
    }

    private volatile Snapshot snapshot = new Snapshot(List.of(), Set.of());

    public void update(List<TopicAssignment> topics, Set<Long> projects) {
        snapshot = new Snapshot(List.copyOf(topics), Set.copyOf(projects));
    }

    public boolean isAssigned(long projectId) {
        return snapshot.projects().contains(projectId);
    }

    public Set<Long> projects() {
        return snapshot.projects();
    }

    public List<TopicAssignment> topics() {
        return snapshot.topics();
    }

    /** Вложенные префиксы разных топиков запрещены при назначении, поэтому совпадение всегда одно. */
    public Optional<String> commandsTopicFor(String path) {
        for (TopicAssignment topic : snapshot.topics()) {
            for (String prefix : topic.pathPrefixes()) {
                if (PathPrefixes.covers(prefix, path)) {
                    return Optional.of(topic.commandsTopic());
                }
            }
        }
        return Optional.empty();
    }
}
