package com.example.editor.controller;

import com.example.editor.dto.template.TemplateCreateDto;
import com.example.editor.dto.template.TemplateResponseDto;
import com.example.editor.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/editor/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    // GET /api/templates - получить все шаблоны
    @GetMapping
    public List<TemplateResponseDto> getAllTemplates() {
        return templateService.getAllTemplates();
    }

    // GET /api/templates/{id} - получить один шаблон
    @GetMapping("/{id}")
    public TemplateResponseDto getTemplate(@PathVariable Long id) {
        return templateService.getTemplateById(id);
    }

    // POST /api/templates - создать шаблон
    @PostMapping
    public TemplateResponseDto createTemplate(@RequestBody TemplateCreateDto dto) {
        return templateService.createTemplate(dto);
    }

    // PUT /api/templates/{id} - обновить шаблон
    @PutMapping("/{id}")
    public TemplateResponseDto updateTemplate(@PathVariable Long id, @RequestBody TemplateCreateDto dto) {
        return templateService.updateTemplate(id, dto);
    }

    // DELETE /api/templates/{id} - удалить шаблон
    @DeleteMapping("/{id}")
    public void deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
    }
}