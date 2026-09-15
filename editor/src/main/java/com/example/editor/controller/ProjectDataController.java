package com.example.editor.controller;

import com.example.editor.dto.data.ProjectDataSaveRequestDto;
import com.example.editor.dto.data.ProjectDataSetDto;
import com.example.editor.service.data.ProjectDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Таблицы данных проекта. История и откат — через {@code DocumentVersionController} с типом
 * {@code data}: {@code /api/editor/data/{projectId}/versions}.
 */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/data")
@RequiredArgsConstructor
public class ProjectDataController {

    private final ProjectDataService projectDataService;

    @GetMapping
    public ProjectDataSetDto get(@PathVariable Long projectId) {
        return projectDataService.get(projectId);
    }

    @PutMapping
    public ProjectDataSetDto save(@PathVariable Long projectId,
                                  @RequestBody ProjectDataSaveRequestDto request,
                                  @RequestHeader("X-Username") String userName) {
        return projectDataService.save(projectId, request, userName);
    }
}
