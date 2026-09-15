package com.example.editor.service.data;

import com.example.editor.dto.data.ProjectDataColumnDto;
import com.example.editor.dto.data.ProjectDataRowDto;
import com.example.editor.dto.data.ProjectDataSaveRequestDto;
import com.example.editor.dto.data.ProjectDataSetDto;
import com.example.editor.dto.data.ProjectDataTableDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.data.ProjectDataTable;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.repository.data.ProjectDataTableRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Таблицы данных проекта. Сохраняются только целиком: одна версия в истории — один согласованный
 * набор. Данные и снимок версии пишутся одной транзакцией.
 */
@Service
@RequiredArgsConstructor
public class ProjectDataService {

    private static final TypeReference<List<ProjectDataColumnDto>> COLUMN_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ProjectDataRowDto>> ROW_LIST = new TypeReference<>() {
    };

    /** По заголовку без учёта регистра, без заголовка — по имени; ручного порядка нет. */
    private static final Comparator<ProjectDataTableDto> BY_TITLE = Comparator
            .comparing((ProjectDataTableDto t) -> t.title() == null ? t.name() : t.title(),
                    String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ProjectDataTableDto::name);

    private final ComponentRepository componentRepository;
    private final ProjectDataTableRepository tableRepository;
    private final ProjectDataValidator validator;
    private final DocumentVersionService versionService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ProjectDataSetDto get(Long projectId) {
        requireProject(projectId);
        return load(projectId, versionService.currentVersionNo(DocumentType.PROJECT_DATA, projectId));
    }

    @Transactional
    public ProjectDataSetDto save(Long projectId, ProjectDataSaveRequestDto request, String userName) {
        requireProject(projectId);
        versionService.requireBase(DocumentType.PROJECT_DATA, projectId, request.basedOnVersion());
        return apply(projectId, request.tables(), userName, VersionKind.MANUAL, request.basedOnVersion());
    }

    /** Запись снимка при восстановлении версии ({@code ProjectDataDocumentSource}); версию не сверяет. */
    @Transactional
    public void restoreSet(Long projectId, ProjectDataSetDto set, String userName) {
        requireProject(projectId);
        apply(projectId, set.tables(), userName, VersionKind.RESTORE, null);
    }

    /** Содержимое для {@code document_version}: без номера версии, иначе хеш дедупликации менялся бы всегда. */
    @Transactional(readOnly = true)
    public ProjectDataSetDto snapshot(Long projectId) {
        return load(projectId, null);
    }

    /** Проект удалён: строки без FK убираются здесь ({@code ComponentServiceImpl.delete}). */
    @Transactional
    public void onProjectDeleted(Long projectId) {
        tableRepository.deleteByProjectId(projectId);
    }

    private ProjectDataSetDto apply(Long projectId, List<ProjectDataTableDto> rawTables, String userName,
                                    VersionKind kind, Integer basedOnVersion) {
        List<ProjectDataTableDto> tables = normalize(rawTables);
        validator.validate(tables);

        // Сопоставление по имени: существующая таблица обновляется на месте, отсутствующая в теле
        // удаляется. «Удалить всё и вставить» упёрлось бы в project_data_table_uk — Hibernate
        // выполняет вставки раньше удалений.
        Map<String, ProjectDataTable> existing = new HashMap<>();
        for (ProjectDataTable table : tableRepository.findByProjectId(projectId)) {
            existing.put(table.getName(), table);
        }
        Set<String> kept = tables.stream().map(ProjectDataTableDto::name).collect(Collectors.toSet());
        tableRepository.deleteAll(existing.values().stream().filter(t -> !kept.contains(t.getName())).toList());
        tableRepository.flush();
        for (ProjectDataTableDto dto : tables) {
            ProjectDataTable entity = existing.containsKey(dto.name()) ? existing.get(dto.name()) : new ProjectDataTable();
            entity.setProjectId(projectId);
            entity.setName(dto.name());
            entity.setTitle(dto.title());
            entity.setDescription(dto.description());
            entity.setColumns(objectMapper.valueToTree(dto.columns()));
            entity.setRows(objectMapper.valueToTree(dto.rows()));
            tableRepository.save(entity);
        }
        tableRepository.flush();

        JsonNode content = objectMapper.valueToTree(load(projectId, null));
        DocumentVersion version = versionService.record(DocumentType.PROJECT_DATA, projectId, content,
                userName, kind, null, basedOnVersion);
        return load(projectId, version.getVersionNo());
    }

    private List<ProjectDataTableDto> normalize(List<ProjectDataTableDto> tables) {
        if (tables == null) {
            return List.of();
        }
        return tables.stream().map(t -> new ProjectDataTableDto(
                        t.name() == null ? null : t.name().trim(),
                        blankToNull(t.title()),
                        blankToNull(t.description()),
                        t.columns() == null ? List.of() : t.columns().stream()
                                .map(c -> new ProjectDataColumnDto(c.name() == null ? null : c.name().trim(),
                                        blankToNull(c.title()), c.valueType(), Boolean.TRUE.equals(c.required()),
                                        c.defaultValue()))
                                .toList(),
                        t.rows() == null ? List.of() : t.rows().stream()
                                .map(r -> new ProjectDataRowDto(r.key(), r.values() == null ? Map.of() : r.values()))
                                .toList()))
                .toList();
    }

    /**
     * Чтение из базы в DTO. Ключи объектов сортируются: jsonb хранит их в своём порядке, а в той же
     * транзакции сущность ещё держит порядок клиента — без сортировки одинаковое содержимое давало
     * бы разный хеш и лишние версии.
     */
    private ProjectDataSetDto load(Long projectId, Integer version) {
        List<ProjectDataTableDto> tables = tableRepository.findByProjectId(projectId).stream()
                .map(e -> new ProjectDataTableDto(e.getName(), e.getTitle(), e.getDescription(),
                        objectMapper.convertValue(e.getColumns(), COLUMN_LIST),
                        objectMapper.convertValue(e.getRows(), ROW_LIST).stream()
                                .map(r -> new ProjectDataRowDto(r.key(), sortedValues(r.values())))
                                .toList()))
                .sorted(BY_TITLE)
                .toList();
        return new ProjectDataSetDto(projectId, version, tables);
    }

    private static Map<String, JsonNode> sortedValues(Map<String, JsonNode> values) {
        Map<String, JsonNode> sorted = new TreeMap<>();
        if (values != null) {
            values.forEach((column, value) -> sorted.put(column, sortedKeys(value)));
        }
        return sorted;
    }

    private static JsonNode sortedKeys(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            names.forEach(name -> sorted.set(name, sortedKeys(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> array.add(sortedKeys(item)));
            return array;
        }
        return node;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireProject(Long projectId) {
        Component project = componentRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        if (!ComponentTypes.PROJECT.equals(project.getType())) {
            throw new IllegalArgumentException("Component " + projectId + " is not a project");
        }
    }
}
