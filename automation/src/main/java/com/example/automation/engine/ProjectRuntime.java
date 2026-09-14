package com.example.automation.engine;

import com.example.automation.definition.DefinitionHash;
import com.example.automation.definition.IoDefinition;
import com.example.automation.definition.ProjectDefinitions;
import com.example.automation.definition.TaskDefinition;
import com.example.automation.definition.VariableDefinition;
import com.example.automation.store.AutomationStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Задачи и watchdog одного проекта, которым владеет экземпляр. */
@Slf4j
final class ProjectRuntime {

    private final int partition;
    private final long epoch;
    private final ProjectDefinitions definitions;
    private final EngineContext context;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<String> watchedTags = new ArrayList<>();

    ProjectRuntime(int partition, long epoch, ProjectDefinitions definitions, EngineContext context) {
        this.partition = partition;
        this.epoch = epoch;
        this.definitions = definitions;
        this.context = context;
    }

    int partition() {
        return partition;
    }

    void start() {
        long projectId = definitions.projectId();
        VariableBoard variables = new VariableBoard(initialVariables(projectId));
        OutputWriter outputs = new OutputWriter(context.commands());

        for (TaskDefinition task : definitions.tasksOrEmpty()) {
            task.inputsOrEmpty().stream().map(IoDefinition::tag).forEach(watchedTags::add);
        }
        context.tags().watch(watchedTags);

        for (TaskDefinition task : definitions.tasksOrEmpty()) {
            String hash = DefinitionHash.of(context.mapper(), task);
            TaskRunner runner = new TaskRunner(projectId, epoch, task, hash, restoredState(projectId, task, hash),
                    context.scripts(), context.tags(), outputs, variables, context.observer(),
                    context.guard()::valid, System::currentTimeMillis, context.mapper());
            futures.add(context.scheduler().scheduleAtFixedRate(() -> context.workers().execute(runner::tick),
                    0, Math.max(100, task.periodMs()), TimeUnit.MILLISECONDS));
        }

        if (definitions.watchdog() != null && definitions.watchdog().tag() != null
                && !definitions.watchdog().tag().isBlank()) {
            WatchdogRunner watchdog = new WatchdogRunner(definitions.watchdog(), context.commands(),
                    context.guard()::valid, System::currentTimeMillis);
            futures.add(context.scheduler().scheduleAtFixedRate(watchdog::tick,
                    watchdog.periodMs(), watchdog.periodMs(), TimeUnit.MILLISECONDS));
        }
        log.info("Project {} started on partition {} (epoch {}): {} task(s), watchdog {}",
                projectId, partition, epoch, definitions.tasksOrEmpty().size(), definitions.watchdog() != null);
    }

    void stop() {
        futures.forEach(future -> future.cancel(false));
        futures.clear();
        context.tags().unwatch(watchedTags);
        watchedTags.clear();
    }

    /** Значения по умолчанию из определения, поверх — последние сохранённые (только объявленные). */
    private Map<String, Object> initialVariables(long projectId) {
        Map<String, Object> values = new HashMap<>();
        for (VariableDefinition variable : definitions.variablesOrEmpty()) {
            values.put(variable.name(), ValueTypes.fromDefault(variable.defaultValue(), variable.valueType()));
        }
        try {
            context.store().loadVariables(projectId).forEach((name, value) -> {
                if (values.containsKey(name)) {
                    values.put(name, value);
                }
            });
        } catch (Exception e) {
            log.warn("Project {}: saved variables not loaded, using defaults: {}", projectId, e.getMessage());
        }
        return values;
    }

    private Map<String, Object> restoredState(long projectId, TaskDefinition task, String hash) {
        try {
            return context.store().loadCheckpoint(projectId, task.id())
                    .filter(checkpoint -> checkpoint.definitionHash().equals(hash))
                    .map(AutomationStore.CheckpointRow::state)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Project {} task {}: checkpoint not loaded, clean start: {}", projectId, task.id(), e.getMessage());
            return null;
        }
    }
}
