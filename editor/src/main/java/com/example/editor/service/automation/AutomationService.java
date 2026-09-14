package com.example.editor.service.automation;

import com.example.editor.dto.automation.AutomationIoDto;
import com.example.editor.dto.automation.AutomationSaveRequestDto;
import com.example.editor.dto.automation.AutomationSetDto;
import com.example.editor.dto.automation.AutomationTaskDto;
import com.example.editor.dto.automation.AutomationVariableDto;
import com.example.editor.dto.automation.AutomationWatchdogDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.automation.AutomationOutbox;
import com.example.editor.model.automation.AutomationTask;
import com.example.editor.model.automation.AutomationVariable;
import com.example.editor.model.automation.AutomationWatchdog;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.automation.AutomationOutboxRepository;
import com.example.editor.repository.automation.AutomationTaskRepository;
import com.example.editor.repository.automation.AutomationVariableRepository;
import com.example.editor.repository.automation.AutomationWatchdogRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Набор автоматизации проекта: задачи, переменные, watchdog. Сохраняется только целиком — одна
 * версия в истории равна одному сообщению в {@code automation.definitions}, и набор никогда не
 * публикуется наполовину.
 * <p>
 * Данные, снимок версии и строка outbox пишутся одной транзакцией: сбой снимка или outbox забирает
 * данные с собой, а сохранённое не может не доехать до Kafka.
 */
@Service
@RequiredArgsConstructor
public class AutomationService {

    static final int DEFAULT_TIMEOUT_MS = 100;
    static final int DEFAULT_WATCHDOG_PERIOD_MS = 1000;

    private static final TypeReference<List<AutomationIoDto>> IO_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> NAME_LIST = new TypeReference<>() {
    };

    private final ComponentRepository componentRepository;
    private final AutomationTaskRepository taskRepository;
    private final AutomationVariableRepository variableRepository;
    private final AutomationWatchdogRepository watchdogRepository;
    private final AutomationOutboxRepository outboxRepository;
    private final AutomationSetValidator validator;
    private final DocumentVersionService versionService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public AutomationSetDto get(Long projectId) {
        requireProject(projectId);
        return load(projectId, versionService.currentVersionNo(DocumentType.AUTOMATION, projectId));
    }

    @Transactional
    public AutomationSetDto save(Long projectId, AutomationSaveRequestDto request, String userName) {
        requireProject(projectId);
        versionService.requireBase(DocumentType.AUTOMATION, projectId, request.basedOnVersion());
        return apply(projectId, request.tasks(), request.variables(), request.watchdog(),
                userName, VersionKind.MANUAL, request.basedOnVersion());
    }

    /**
     * Запись снимка обратно при восстановлении версии ({@code AutomationDocumentSource}). Версию
     * не сверяет — восстановление её не присылает; проверку набора проходит, как обычное сохранение.
     */
    @Transactional
    public void restoreSet(Long projectId, AutomationSetDto set, String userName) {
        requireProject(projectId);
        apply(projectId, set.tasks(), set.variables(), set.watchdog(), userName, VersionKind.RESTORE, null);
    }

    /** Содержимое для {@code document_version}: без номера версии, иначе хеш дедупликации менялся бы всегда. */
    @Transactional(readOnly = true)
    public AutomationSetDto snapshot(Long projectId) {
        return load(projectId, null);
    }

    /** Служебное: положить текущий набор в outbox заново — ручное восстановление топика. */
    @Transactional
    public void republish(Long projectId) {
        requireProject(projectId);
        Integer current = versionService.currentVersionNo(DocumentType.AUTOMATION, projectId);
        if (current == null) {
            throw new IllegalStateException("Project " + projectId + " has no saved automation yet");
        }
        enqueue(projectId, current, objectMapper.valueToTree(load(projectId, current)));
    }

    /**
     * Проект удалён: строки без FK убираются здесь, в топик уходит tombstone — {@code automation}
     * останавливает задачи проекта. Проекту, у которого автоматизации не было, tombstone не нужен.
     */
    @Transactional
    public void onProjectDeleted(Long projectId) {
        boolean hadAutomation = versionService.currentVersionNo(DocumentType.AUTOMATION, projectId) != null;
        taskRepository.deleteByProjectId(projectId);
        variableRepository.deleteByProjectId(projectId);
        watchdogRepository.deleteById(projectId);
        if (hadAutomation) {
            enqueue(projectId, null, null);
        }
    }

    private AutomationSetDto apply(Long projectId, List<AutomationTaskDto> rawTasks,
                                   List<AutomationVariableDto> rawVariables, AutomationWatchdogDto rawWatchdog,
                                   String userName, VersionKind kind, Integer basedOnVersion) {
        List<AutomationTaskDto> tasks = normalize(rawTasks);
        List<AutomationVariableDto> variables = rawVariables == null ? List.of() : rawVariables;
        AutomationWatchdogDto watchdog = rawWatchdog == null
                ? null
                : new AutomationWatchdogDto(rawWatchdog.tag(),
                        rawWatchdog.periodMs() == null ? DEFAULT_WATCHDOG_PERIOD_MS : rawWatchdog.periodMs());
        validator.validate(tasks, variables, watchdog);

        // Задачи сопоставляются по id: он же ключ контрольной точки в automation, и пересохранение
        // без правок не должно сбрасывать память регулятора. Id чужого проекта или удалённой
        // задачи (снимок старой версии) — задача создаётся заново.
        Map<Long, AutomationTask> existing = new HashMap<>();
        for (AutomationTask task : taskRepository.findByProjectIdOrderByIdAsc(projectId)) {
            existing.put(task.getId(), task);
        }
        Set<Long> kept = tasks.stream()
                .map(AutomationTaskDto::id)
                .filter(Objects::nonNull)
                .filter(existing::containsKey)
                .collect(Collectors.toSet());
        taskRepository.deleteAll(existing.values().stream().filter(t -> !kept.contains(t.getId())).toList());
        taskRepository.flush();
        for (AutomationTaskDto dto : tasks) {
            AutomationTask entity = dto.id() != null && existing.containsKey(dto.id())
                    ? existing.get(dto.id())
                    : new AutomationTask();
            entity.setProjectId(projectId);
            copy(dto, entity);
            taskRepository.save(entity);
        }

        variableRepository.deleteByProjectId(projectId);
        variableRepository.flush();
        variableRepository.saveAll(variables.stream().map(v -> toEntity(projectId, v)).toList());

        if (watchdog == null) {
            watchdogRepository.deleteById(projectId);
        } else {
            AutomationWatchdog entity = new AutomationWatchdog();
            entity.setProjectId(projectId);
            entity.setTag(watchdog.tag());
            entity.setPeriodMs(watchdog.periodMs());
            watchdogRepository.save(entity);
        }
        taskRepository.flush();

        Integer before = versionService.currentVersionNo(DocumentType.AUTOMATION, projectId);
        JsonNode content = objectMapper.valueToTree(load(projectId, null));
        DocumentVersion version = versionService.record(DocumentType.AUTOMATION, projectId, content,
                userName, kind, null, basedOnVersion);
        // record() вернул прежнюю версию — содержимое не изменилось, публиковать нечего.
        if (!Objects.equals(version.getVersionNo(), before)) {
            enqueue(projectId, version.getVersionNo(),
                    objectMapper.valueToTree(load(projectId, version.getVersionNo())));
        }
        return load(projectId, version.getVersionNo());
    }

    private List<AutomationTaskDto> normalize(List<AutomationTaskDto> tasks) {
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream().map(t -> new AutomationTaskDto(
                t.id(),
                t.name() == null ? null : t.name().trim(),
                Boolean.TRUE.equals(t.enabled()),
                t.periodMs(),
                t.timeoutMs() == null ? DEFAULT_TIMEOUT_MS : t.timeoutMs(),
                t.staleAfterMs(),
                Boolean.TRUE.equals(t.runOnStale()),
                t.inputs() == null ? List.of() : t.inputs(),
                t.outputs() == null ? List.of() : t.outputs(),
                t.writesVariables() == null ? List.of() : t.writesVariables(),
                t.script() == null ? "" : t.script())).toList();
    }

    private void copy(AutomationTaskDto dto, AutomationTask entity) {
        entity.setName(dto.name());
        entity.setEnabled(dto.enabled());
        entity.setPeriodMs(dto.periodMs());
        entity.setTimeoutMs(dto.timeoutMs());
        entity.setStaleAfterMs(dto.staleAfterMs());
        entity.setRunOnStale(dto.runOnStale());
        entity.setInputs(objectMapper.valueToTree(dto.inputs()));
        entity.setOutputs(objectMapper.valueToTree(dto.outputs()));
        entity.setWritesVariables(objectMapper.valueToTree(dto.writesVariables()));
        entity.setScript(dto.script());
    }

    private AutomationVariable toEntity(Long projectId, AutomationVariableDto dto) {
        AutomationVariable entity = new AutomationVariable();
        entity.setProjectId(projectId);
        entity.setName(dto.name());
        entity.setValueType(dto.valueType());
        entity.setDefaultValue(dto.defaultValue());
        entity.setDescription(dto.description());
        return entity;
    }

    private AutomationSetDto load(Long projectId, Integer version) {
        List<AutomationTaskDto> tasks = taskRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(e -> new AutomationTaskDto(e.getId(), e.getName(), e.isEnabled(), e.getPeriodMs(),
                        e.getTimeoutMs(), e.getStaleAfterMs(), e.isRunOnStale(),
                        objectMapper.convertValue(e.getInputs(), IO_LIST),
                        objectMapper.convertValue(e.getOutputs(), IO_LIST),
                        objectMapper.convertValue(e.getWritesVariables(), NAME_LIST),
                        e.getScript()))
                .toList();
        List<AutomationVariableDto> variables = variableRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(v -> new AutomationVariableDto(v.getName(), v.getValueType(), v.getDefaultValue(), v.getDescription()))
                .toList();
        AutomationWatchdogDto watchdog = watchdogRepository.findById(projectId)
                .map(w -> new AutomationWatchdogDto(w.getTag(), w.getPeriodMs()))
                .orElse(null);
        return new AutomationSetDto(projectId, version, tasks, variables, watchdog);
    }

    private void enqueue(Long projectId, Integer version, JsonNode payload) {
        AutomationOutbox row = new AutomationOutbox();
        row.setProjectId(projectId);
        row.setDefinitionsVersion(version);
        row.setPayload(payload);
        row.setCreatedAt(LocalDateTime.now());
        outboxRepository.save(row);
        eventPublisher.publishEvent(new AutomationOutboxEnqueued(projectId));
    }

    private void requireProject(Long projectId) {
        Component project = componentRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        if (!ComponentTypes.PROJECT.equals(project.getType())) {
            throw new IllegalArgumentException("Component " + projectId + " is not a project");
        }
    }
}
