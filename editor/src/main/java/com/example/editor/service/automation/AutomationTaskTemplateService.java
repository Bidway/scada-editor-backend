package com.example.editor.service.automation;

import com.example.editor.dto.automation.AutomationTaskTemplateDto;
import com.example.editor.dto.automation.AutomationTemplateIoDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.automation.AutomationTaskTemplate;
import com.example.editor.repository.automation.AutomationTaskTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Палитра шаблонов задач: общий для всех проектов CRUD без истории версий (решение пользователя
 * 15.09.2026 — сохранение перезаписывает шаблон).
 * <p>
 * Связи с созданными из шаблона задачами нет: задача — копия, и правка шаблона её не трогает.
 * Превращения задача ↔ шаблон делает фронт, здесь только хранение и проверка.
 */
@Service
@RequiredArgsConstructor
public class AutomationTaskTemplateService {

    private static final TypeReference<List<AutomationTemplateIoDto>> IO_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> NAME_LIST = new TypeReference<>() {
    };

    private final AutomationTaskTemplateRepository repository;
    private final AutomationTaskTemplateValidator validator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<AutomationTaskTemplateDto> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AutomationTaskTemplateDto get(Long id) {
        return toDto(require(id));
    }

    @Transactional
    public AutomationTaskTemplateDto create(AutomationTaskTemplateDto request) {
        AutomationTaskTemplateDto template = normalize(request);
        validator.validate(template, null);
        return toDto(repository.save(copy(template, new AutomationTaskTemplate())));
    }

    @Transactional
    public AutomationTaskTemplateDto update(Long id, AutomationTaskTemplateDto request) {
        AutomationTaskTemplate entity = require(id);
        AutomationTaskTemplateDto template = normalize(request);
        validator.validate(template, id);
        return toDto(repository.save(copy(template, entity)));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(require(id));
    }

    private AutomationTaskTemplate require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Automation task template not found: " + id));
    }

    /** Те же умолчания, что у задачи в {@code AutomationService.normalize}: расходиться им незачем. */
    private AutomationTaskTemplateDto normalize(AutomationTaskTemplateDto t) {
        return new AutomationTaskTemplateDto(
                t.id(),
                t.name() == null ? null : t.name().trim(),
                t.category() == null || t.category().isBlank() ? null : t.category().trim(),
                t.description(),
                t.periodMs(),
                t.timeoutMs() == null ? AutomationService.DEFAULT_TIMEOUT_MS : t.timeoutMs(),
                t.staleAfterMs(),
                Boolean.TRUE.equals(t.runOnStale()),
                t.inputs() == null ? List.of() : t.inputs(),
                t.outputs() == null ? List.of() : t.outputs(),
                t.writesVariables() == null ? List.of() : t.writesVariables(),
                t.script() == null ? "" : t.script());
    }

    private AutomationTaskTemplate copy(AutomationTaskTemplateDto dto, AutomationTaskTemplate entity) {
        entity.setName(dto.name());
        entity.setCategory(dto.category());
        entity.setDescription(dto.description());
        entity.setPeriodMs(dto.periodMs());
        entity.setTimeoutMs(dto.timeoutMs());
        entity.setStaleAfterMs(dto.staleAfterMs());
        entity.setRunOnStale(dto.runOnStale());
        entity.setInputs(objectMapper.valueToTree(dto.inputs()));
        entity.setOutputs(objectMapper.valueToTree(dto.outputs()));
        entity.setWritesVariables(objectMapper.valueToTree(dto.writesVariables()));
        entity.setScript(dto.script());
        return entity;
    }

    private AutomationTaskTemplateDto toDto(AutomationTaskTemplate e) {
        return new AutomationTaskTemplateDto(e.getId(), e.getName(), e.getCategory(), e.getDescription(),
                e.getPeriodMs(), e.getTimeoutMs(), e.getStaleAfterMs(), e.isRunOnStale(),
                objectMapper.convertValue(e.getInputs(), IO_LIST),
                objectMapper.convertValue(e.getOutputs(), IO_LIST),
                objectMapper.convertValue(e.getWritesVariables(), NAME_LIST),
                e.getScript());
    }
}
