package com.example.runtime.controller;

import com.example.runtime.dto.TagWriteRequest;
import com.example.runtime.dto.TagWriteResult;
import com.example.runtime.write.TagWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @Operation(summary = "Записать значения тегов в ПЛК. Всегда массив — единичная запись "
            + "это массив из одного элемента, отдельной ручки под этот случай нет")
    @PostMapping("/write")
    public ResponseEntity<List<TagWriteResult>> write(@Valid @RequestBody TagWriteRequest request) {
        return ResponseEntity.ok(tagWriteService.write(request));
    }
}
