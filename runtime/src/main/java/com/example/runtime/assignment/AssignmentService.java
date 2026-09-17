package com.example.runtime.assignment;

import com.example.runtime.client.EditorClient;
import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.instance.InstanceEntity;
import com.example.runtime.instance.InstanceRepository;
import com.example.runtime.session.TagSubscriptionIndex;
import com.example.runtime.session.VariableTags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Назначение топиков и проектов экземплярам — только вручную. Перенос делается в два шага: снять у
 * одного, назначить другому. Запроса «переместить» нет: это была бы та же автоматика, спрятанная в API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    public record TopicView(String telemetryTopic, String commandsTopic, String resultsTopic,
                            List<String> pathPrefixes) {
    }

    public record InstanceView(String instanceId, String baseUrl, String description, Instant lastSeenAt,
                               List<TopicView> topics, List<Long> projects) {
    }

    private final InstanceRepository instances;
    private final InstanceTopicRepository topics;
    private final InstanceTopicPrefixRepository prefixes;
    private final InstanceProjectRepository projects;
    private final EditorClient editorClient;

    @Transactional
    public void assignTopic(String instanceId, String telemetryTopic, String commandsTopic, String resultsTopic,
                            List<String> pathPrefixes, String username) {
        requireInstance(instanceId);
        if (pathPrefixes == null || pathPrefixes.isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы один префикс пути: без него команды некуда адресовать");
        }
        topics.findById(telemetryTopic).ifPresent(existing -> {
            if (!existing.getInstanceId().equals(instanceId)) {
                throw new AssignmentConflictException("Топик " + telemetryTopic + " уже назначен экземпляру "
                        + existing.getInstanceId() + " — сначала снимите его там");
            }
        });
        if (topics.existsByCommandsTopicAndTelemetryTopicNot(commandsTopic, telemetryTopic)) {
            throw new AssignmentConflictException("Топик команд " + commandsTopic + " уже принадлежит другому топику телеметрии");
        }
        if (topics.existsByResultsTopicAndTelemetryTopicNot(resultsTopic, telemetryTopic)) {
            throw new AssignmentConflictException("Топик результатов " + resultsTopic + " уже принадлежит другому топику телеметрии");
        }
        for (InstanceTopicPrefixEntity other : prefixes.findAll()) {
            if (other.getTelemetryTopic().equals(telemetryTopic)) {
                continue;
            }
            for (String prefix : pathPrefixes) {
                if (PathPrefixes.overlap(prefix, other.getPathPrefix())) {
                    throw new AssignmentConflictException("Префикс " + prefix + " пересекается с "
                            + other.getPathPrefix() + " топика " + other.getTelemetryTopic());
                }
            }
        }
        InstanceTopicEntity row = topics.findById(telemetryTopic).orElseGet(InstanceTopicEntity::new);
        row.setTelemetryTopic(telemetryTopic);
        row.setCommandsTopic(commandsTopic);
        row.setResultsTopic(resultsTopic);
        row.setInstanceId(instanceId);
        row.setAssignedBy(username);
        row.setAssignedAt(Instant.now());
        topics.save(row);
        prefixes.deleteByTelemetryTopic(telemetryTopic);
        prefixes.flush();
        for (String prefix : pathPrefixes) {
            InstanceTopicPrefixEntity p = new InstanceTopicPrefixEntity();
            p.setPathPrefix(prefix);
            p.setTelemetryTopic(telemetryTopic);
            prefixes.save(p);
        }
        log.info("Топик {} назначен экземпляру {} (команды {}, результаты {}, префиксы {}) пользователем {}",
                telemetryTopic, instanceId, commandsTopic, resultsTopic, pathPrefixes, username);
    }

    @Transactional
    public void removeTopic(String instanceId, String telemetryTopic) {
        InstanceTopicEntity row = topics.findById(telemetryTopic)
                .filter(t -> t.getInstanceId().equals(instanceId))
                .orElseThrow(() -> new IllegalArgumentException("Топик " + telemetryTopic
                        + " не назначен экземпляру " + instanceId));
        prefixes.deleteByTelemetryTopic(telemetryTopic);
        topics.delete(row);
        log.info("Топик {} снят с экземпляра {}", telemetryTopic, instanceId);
    }

    @Transactional
    public void assignProject(String instanceId, long projectId, String username) {
        requireInstance(instanceId);
        projects.findById(projectId).ifPresent(existing -> {
            if (!existing.getInstanceId().equals(instanceId)) {
                throw new AssignmentConflictException("Проект " + projectId + " уже назначен экземпляру "
                        + existing.getInstanceId() + " — сначала снимите его там");
            }
        });
        List<String> uncovered = uncoveredPaths(instanceId, projectId);
        if (!uncovered.isEmpty()) {
            throw new AssignmentConflictException("Теги проекта " + projectId
                    + " не покрыты топиками экземпляра " + instanceId, uncovered);
        }
        InstanceProjectEntity row = projects.findById(projectId).orElseGet(InstanceProjectEntity::new);
        row.setProjectId(projectId);
        row.setInstanceId(instanceId);
        row.setAssignedBy(username);
        row.setAssignedAt(Instant.now());
        projects.save(row);
        log.info("Проект {} назначен экземпляру {} пользователем {}", projectId, instanceId, username);
    }

    @Transactional
    public void removeProject(String instanceId, long projectId) {
        InstanceProjectEntity row = projects.findById(projectId)
                .filter(p -> p.getInstanceId().equals(instanceId))
                .orElseThrow(() -> new IllegalArgumentException("Проект " + projectId
                        + " не назначен экземпляру " + instanceId));
        projects.delete(row);
        log.info("Проект {} снят с экземпляра {}", projectId, instanceId);
    }

    @Transactional(readOnly = true)
    public List<InstanceView> listInstances() {
        List<InstanceView> result = new ArrayList<>();
        for (InstanceEntity instance : instances.findAll()) {
            List<TopicView> topicViews = topics.findByInstanceId(instance.getInstanceId()).stream()
                    .map(t -> new TopicView(t.getTelemetryTopic(), t.getCommandsTopic(), t.getResultsTopic(),
                            prefixes.findByTelemetryTopic(t.getTelemetryTopic()).stream()
                                    .map(InstanceTopicPrefixEntity::getPathPrefix).sorted().toList()))
                    .toList();
            List<Long> projectIds = projects.findByInstanceId(instance.getInstanceId()).stream()
                    .map(InstanceProjectEntity::getProjectId).sorted().toList();
            result.add(new InstanceView(instance.getInstanceId(), instance.getBaseUrl(), instance.getDescription(),
                    instance.getLastSeenAt(), topicViews, projectIds));
        }
        return result;
    }

    /** Пути тегов проекта, не покрытые префиксами топиков экземпляра. Переменные {@code @var.*} — не теги ПЛК. */
    private List<String> uncoveredPaths(String instanceId, long projectId) {
        EditorComponentDto tree = editorClient.getProjectTree(projectId);
        if (tree == null) {
            throw new IllegalArgumentException("Проект " + projectId + " не найден в editor");
        }
        List<String> own = prefixes.findByTelemetryTopicIn(topics.findByInstanceId(instanceId).stream()
                        .map(InstanceTopicEntity::getTelemetryTopic).toList())
                .stream().map(InstanceTopicPrefixEntity::getPathPrefix).toList();
        return TagSubscriptionIndex.build(tree, projectId).getAllTagIds().stream()
                .filter(path -> !VariableTags.isVariableKey(path))
                .filter(path -> own.stream().noneMatch(prefix -> PathPrefixes.covers(prefix, path)))
                .sorted()
                .toList();
    }

    private void requireInstance(String instanceId) {
        if (!instances.existsById(instanceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Экземпляр runtime " + instanceId + " неизвестен: он ни разу не стартовал");
        }
    }
}
