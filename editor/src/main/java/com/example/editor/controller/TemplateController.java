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

    @GetMapping
    public List<TemplateResponseDto> getAllTemplates() {
        return templateService.getAllTemplates();
    }

    @GetMapping("/{id}")
    public TemplateResponseDto getTemplate(@PathVariable Long id) {
        return templateService.getTemplateById(id);
    }

    @PostMapping
    public TemplateResponseDto createTemplate(
            @RequestBody TemplateCreateDto dto,
            @RequestHeader("X-Username") String userName) {
        return templateService.createTemplate(dto, userName);
    }

    @PutMapping("/{id}")
    public TemplateResponseDto updateTemplate(
            @PathVariable Long id,
            @RequestBody TemplateCreateDto dto,
            @RequestHeader("X-Username") String userName) {
        return templateService.updateTemplate(id, dto, userName);
    }

    @DeleteMapping("/{id}")
    public void deleteTemplate(
            @PathVariable Long id,
            @RequestHeader("X-Username") String userName) {
        templateService.deleteTemplate(id, userName);
    }
}
