package com.example.editor.controller;

import com.example.editor.dto.autobind.AutobindReportDto;
import com.example.editor.dto.autobind.AutobindRequestDto;
import com.example.editor.service.autobind.AutobindService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Автопривязка всех компонентов проекта к выбранной базе каналов. */
@RestController
@RequestMapping("/api/editor/projects/{projectId}/autobind")
@RequiredArgsConstructor
public class AutobindController {

    private final AutobindService service;

    @Operation(summary = "Теги из базы каналов по имени компонента (объект) и свойства (поле)")
    @PostMapping
    public AutobindReportDto autobind(@PathVariable Long projectId, @RequestBody AutobindRequestDto request,
                                      @RequestHeader(value = "X-Username", required = false) String username) {
        return service.autobind(projectId, request.channelRoot(), username);
    }
}
