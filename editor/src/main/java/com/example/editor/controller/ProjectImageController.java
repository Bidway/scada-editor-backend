package com.example.editor.controller;

import com.example.editor.exception.NotFoundException;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Настройки рабочего места проекта — сейчас это закреплённые схемы, общие для всех в редакторе и
 * мониторе. Формат принадлежит фронту: бэк хранит JSON-объект как есть и не разбирает его.
 * <p>
 * Это не правка схемы: версий документа не создаёт, в выпуск не входит, в runtime.projects не
 * публикуется. Запись заменяет объект целиком, последняя побеждает. Читается в списке проектов
 * ({@code GET /api/editor/components/projects}), отдельного GET нет.
 */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/image")
@RequiredArgsConstructor
@Slf4j
public class ProjectImageController {

    /** Закрепления — десятки id; предел только от мусора, а не под формат. */
    static final int MAX_BYTES = 16 * 1024;

    private final ComponentRepository components;
    private final ProjectRuntimeFlagRepository repository;
    private final ObjectMapper objectMapper;

    @PutMapping
    public Map<String, JsonNode> put(@PathVariable Long projectId,
                                     @RequestBody Map<String, JsonNode> body,
                                     @RequestHeader(value = "X-Username", required = false) String username)
            throws Exception {
        JsonNode image = body.get("image");
        if (image == null || !image.isObject()) {
            throw new IllegalArgumentException("image должен быть JSON-объектом");
        }
        String json = objectMapper.writeValueAsString(image);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("image больше " + MAX_BYTES + " байт");
        }
        components.findById(projectId)
                .filter(c -> ComponentTypes.PROJECT.equals(c.getType()))
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        repository.upsertImage(projectId, json);
        log.info("Проект {}: записан image (пользователь {})", projectId, username);
        return Map.of("image", image);
    }
}
