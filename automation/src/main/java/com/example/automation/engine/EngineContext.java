package com.example.automation.engine;

import com.example.automation.kafka.OwnershipGuard;
import com.example.automation.store.AutomationStore;
import com.example.scriptcore.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Общие для всех проектов экземпляра зависимости движка. */
record EngineContext(ScheduledExecutorService scheduler, ExecutorService workers, SandboxExecutor scripts,
                     TagCache tags, CommandSender commands, TaskObserver observer, OwnershipGuard guard,
                     AutomationStore store, ObjectMapper mapper) {
}
