package com.example.runtime.controller;

import com.example.runtime.dto.PropertyWriteRequest;
import com.example.runtime.dto.PropertyWriteResult;
import com.example.runtime.journal.ActionJournal;
import com.example.runtime.journal.ActionRecord;
import com.example.runtime.write.PropertyWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Запись локальных свойств компонентов из инспектора объектов монитора. */
@RestController
@RequestMapping("/api/runtime/projects/{projectId}/properties")
@RequiredArgsConstructor
@Tag(name = "Properties", description = "Запись значений локальных свойств оператором")
public class PropertyWriteController {

    private final PropertyWriteService propertyWriteService;
    private final ActionJournal journal;

    @Operation(summary = "Записать значения локальных свойств (без тега). Всегда массив; "
            + "свойства с тегом отклоняются — их пишут через /api/runtime/tags/write")
    @PostMapping("/write")
    public ResponseEntity<List<PropertyWriteResult>> write(@PathVariable Long projectId,
                                                           @Valid @RequestBody PropertyWriteRequest request,
                                                           @RequestHeader(value = "X-Username", required = false) String username) {
        // В журнале — как у TAG_WRITE: что пытались записать, только свойство вместо тега.
        List<Map<String, Object>> props = request.getWrites().stream()
                .map(w -> Map.<String, Object>of("property", w.getPropertyId(), "value", w.getValue()))
                .toList();
        ActionRecord base = new ActionRecord(Instant.now(), username, projectId,
                ActionRecord.KIND_PROPERTY_WRITE, null, null, null, props, null, null);
        List<PropertyWriteResult> results;
        try {
            results = propertyWriteService.write(projectId, request.getWrites());
        } catch (RuntimeException e) {
            journal.record(base.failed(e.getMessage()));
            throw e;
        }
        String failures = results.stream().filter(r -> !r.success())
                .map(r -> r.propertyId() + ": " + r.status()).collect(Collectors.joining("; "));
        journal.record(failures.isEmpty() ? base.ok() : base.failed(failures));
        return ResponseEntity.ok(results);
    }
}
