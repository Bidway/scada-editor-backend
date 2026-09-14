package com.example.automation.api;

import com.example.automation.store.AutomationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Статусы задач проекта из общей базы — отвечает любой экземпляр, не только владелец. */
@RestController
@RequestMapping("/api/automation/projects/{projectId}/tasks")
@RequiredArgsConstructor
public class TaskStatusController {

    private final AutomationStore store;

    @GetMapping
    public List<AutomationStore.StatusRow> statuses(@PathVariable long projectId) {
        return store.statuses(projectId);
    }
}
