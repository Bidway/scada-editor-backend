package com.example.editor.controller;

import com.example.editor.dto.component.ComponentSaveResponseDto;
import com.example.editor.dto.version.DocumentVersionDto;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.DocumentVersion;
import com.example.editor.model.version.VersionKind;
import com.example.editor.service.version.DocumentVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * История документа. Один набор эндпоинтов на сцены и шаблоны — вид документа задаётся в пути
 * ({@code scenes} либо {@code templates}), потому что механизм у них общий, а различаются
 * только производители содержимого.
 * <p>
 * С путями существующих контроллеров это не конфликтует: у {@code TemplateController} на
 * {@code /api/editor/templates/{id}} два сегмента, здесь — три и больше.
 */
@RestController
@RequestMapping("/api/editor/{type}")
@RequiredArgsConstructor
public class DocumentVersionController {

    private final DocumentVersionService versionService;

    @GetMapping("/{id}/versions")
    public List<DocumentVersionDto> versions(
            @PathVariable String type,
            @PathVariable Long id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<VersionKind> kind,
            @RequestParam(required = false) Integer limit) {
        return versionService.list(documentType(type), id, from, to, kind, limit);
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public JsonNode version(@PathVariable String type, @PathVariable Long id,
                            @PathVariable Integer versionNo) {
        return versionService.require(documentType(type), id, versionNo).getContent();
    }

    /**
     * Состояние на момент времени — это последняя версия, созданная не позже него. История
     * состоит из точек сохранения, а не из непрерывной записи: правка в 13:58 при ближайшем
     * сохранении в 14:05 означает, что запрос на 14:00 вернёт состояние на 13:50.
     */
    @GetMapping("/{id}/at")
    public JsonNode at(@PathVariable String type, @PathVariable Long id,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                       LocalDateTime time) {
        return versionService.contentAt(documentType(type), id, time);
    }

    @PostMapping("/{id}/restore/{versionNo}")
    public ComponentSaveResponseDto restore(@PathVariable String type, @PathVariable Long id,
                                            @PathVariable Integer versionNo,
                                            @RequestHeader("X-Username") String userName) {
        DocumentVersion created = versionService.restore(documentType(type), id, versionNo, userName);
        return new ComponentSaveResponseDto(
                created.getContent(), created.getVersionNo(), created.getRestoredFrom());
    }

    static DocumentType documentType(String type) {
        return switch (type) {
            case "scenes" -> DocumentType.SCENE;
            case "templates" -> DocumentType.TEMPLATE;
            default -> throw new IllegalArgumentException(
                    "Unknown document type: " + type + "; expected scenes or templates");
        };
    }
}
