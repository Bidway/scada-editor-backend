package com.example.editor.controller;

import com.example.editor.dto.automation.AutomationTaskTemplateDto;
import com.example.editor.service.automation.AutomationTaskTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Палитра шаблонов задач автоматизации — общая для всех проектов, поэтому вне
 * {@code /projects/{projectId}}.
 * <p>
 * {@code X-Username} не читается: истории у шаблонов нет, автора записывать некуда.
 */
@RestController
@RequestMapping("/api/editor/automation-templates")
@RequiredArgsConstructor
public class AutomationTemplateController {

    private final AutomationTaskTemplateService templateService;

    @GetMapping
    public List<AutomationTaskTemplateDto> list() {
        return templateService.list();
    }

    @GetMapping("/{id}")
    public AutomationTaskTemplateDto get(@PathVariable Long id) {
        return templateService.get(id);
    }

    @PostMapping
    public AutomationTaskTemplateDto create(@RequestBody AutomationTaskTemplateDto request) {
        return templateService.create(request);
    }

    @PutMapping("/{id}")
    public AutomationTaskTemplateDto update(@PathVariable Long id,
                                            @RequestBody AutomationTaskTemplateDto request) {
        return templateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ResponseEntity.ok().build();
    }
}
