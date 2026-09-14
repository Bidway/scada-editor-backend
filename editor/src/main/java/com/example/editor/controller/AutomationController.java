package com.example.editor.controller;

import com.example.editor.dto.automation.AutomationSaveRequestDto;
import com.example.editor.dto.automation.AutomationSetDto;
import com.example.editor.service.automation.AutomationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Набор автоматизации проекта. История и откат — через {@code DocumentVersionController} с типом
 * {@code automation}: {@code /api/editor/automation/{projectId}/versions}.
 */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/automation")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationService automationService;

    @GetMapping
    public AutomationSetDto get(@PathVariable Long projectId) {
        return automationService.get(projectId);
    }

    @PutMapping
    public AutomationSetDto save(@PathVariable Long projectId,
                                 @RequestBody AutomationSaveRequestDto request,
                                 @RequestHeader("X-Username") String userName) {
        return automationService.save(projectId, request, userName);
    }

    @PostMapping("/republish")
    public ResponseEntity<Void> republish(@PathVariable Long projectId) {
        automationService.republish(projectId);
        return ResponseEntity.accepted().build();
    }
}
