package com.example.runtime.automation.api;

import com.example.runtime.automation.store.AutomationStore;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Статусы фоновых задач проекта из базы. Путь прежний — фронт не меняется. */
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
