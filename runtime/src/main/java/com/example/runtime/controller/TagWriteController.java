package com.example.runtime.controller;

import com.example.runtime.dto.TagWriteRequest;
import com.example.runtime.dto.TagWriteResult;
import com.example.runtime.journal.ActionJournal;
import com.example.runtime.journal.ActionRecord;
import com.example.runtime.write.TagWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Точечная запись тегов в ПЛК из «Опций» компонента в мониторе — в обход скриптов,
 * произвольным значением по выбору оператора.
 */
@RestController
@RequestMapping("/api/runtime/tags")
@RequiredArgsConstructor
@Tag(name = "Tags", description = "Точечная запись значений тегов в ПЛК")
public class TagWriteController {

    private final TagWriteService tagWriteService;
    private final ActionJournal journal;

    @Operation(summary = "Записать значения тегов в ПЛК. Всегда массив — единичная запись "
            + "это массив из одного элемента, отдельной ручки под этот случай нет")
    @PostMapping("/write")
    public ResponseEntity<List<TagWriteResult>> write(@Valid @RequestBody TagWriteRequest request,
                                                      @RequestHeader(value = "X-Username", required = false) String username) {
        List<Map<String, Object>> tags = request.getWrites().stream()
                .map(w -> Map.<String, Object>of("tag", w.getTagId(), "value", w.getValue()))
                .toList();
        ActionRecord base = new ActionRecord(Instant.now(), username, request.getProjectId(),
                ActionRecord.KIND_TAG_WRITE, null, null, null, tags, null, null);
        List<TagWriteResult> results;
        try {
            results = tagWriteService.write(request);
        } catch (RuntimeException e) {
            journal.record(base.failed(e.getMessage()));
            throw e;
        }
        // Частичный отказ контроллера — не исключение, а success=false в ответе; это тоже ERROR.
        String failures = results.stream().filter(r -> !r.success())
                .map(r -> r.tagId() + ": " + r.status()).collect(Collectors.joining("; "));
        journal.record(failures.isEmpty() ? base.ok() : base.failed(failures));
        return ResponseEntity.ok(results);
    }
}
