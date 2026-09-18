package com.example.runtime.automation.engine;

import com.example.runtime.automation.definition.DefinitionHash;
import com.example.runtime.automation.definition.IoDefinition;
import com.example.runtime.automation.definition.ProjectDefinitions;
import com.example.runtime.automation.definition.TaskDefinition;
import com.example.runtime.automation.definition.VariableDefinition;
import com.example.runtime.automation.store.AutomationStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Задачи и watchdog одного поднятого проекта. Бывший ProjectRuntime сервиса automation. */
@Slf4j
final class ProjectAutomation {

    private final ProjectDefinitions definitions;
    private final AutomationEngine.Context context;
    private final BooleanSupplier projectUp;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<String> watchedTags = new ArrayList<>();
    private ProjectDataHolder data;

    ProjectAutomation(ProjectDefinitions definitions, AutomationEngine.Context context, BooleanSupplier projectUp) {
        this.definitions = definitions;
        this.context = context;
        this.projectUp = projectUp;
    }

    void start() {
        long projectId = definitions.projectId();
        ProjectDataHolder holder = new ProjectDataHolder(projectId, context.dataFetcher(), context.dataLoader(),
                context.dataRetryMinMs(), context.dataRetryMaxMs());
        data = holder;
        holder.start();
        VariableBoard variables = new VariableBoard(initialVariables(projectId));
        OutputWriter outputs = new OutputWriter(context.commands(), context.tags(), System::currentTimeMillis);

        for (TaskDefinition task : definitions.tasksOrEmpty()) {
            task.inputsOrEmpty().stream().map(IoDefinition::tag).forEach(watchedTags::add);
            // Выходы тоже: по их телеметрии OutputWriter видит чужую запись в тег (scada-cre).
            task.outputsOrEmpty().stream().map(IoDefinition::tag).forEach(watchedTags::add);
        }
        context.tags().watch(watchedTags);

        for (TaskDefinition task : definitions.tasksOrEmpty()) {
            String hash = DefinitionHash.of(context.mapper(), task);
            TaskRunner runner = new TaskRunner(projectId, task, hash, restoredState(projectId, task, hash),
                    context.scripts(), context.tags(), outputs, variables, holder::current, context.observer(),
                    projectUp, System::currentTimeMillis, context.mapper());
            long periodMs = Math.max(100, task.periodMs());
            long firstAtMs = System.currentTimeMillis();
            AtomicLong tickNumber = new AtomicLong();
            // Ожидаемое начало считается от расписания, а не от момента постановки в пул: так в опоздание
            // попадает и очередь пула, и задержка самого планировщика.
            futures.add(context.scheduler().scheduleAtFixedRate(() -> {
                long expectedStartMs = firstAtMs + tickNumber.getAndIncrement() * periodMs;
                context.workers().execute(() -> runner.tick(expectedStartMs));
            }, 0, periodMs, TimeUnit.MILLISECONDS));
        }

        if (definitions.watchdog() != null && definitions.watchdog().tag() != null
                && !definitions.watchdog().tag().isBlank()) {
            WatchdogRunner watchdog = new WatchdogRunner(definitions.watchdog(), context.commands(), projectUp,
                    System::currentTimeMillis);
            futures.add(context.scheduler().scheduleAtFixedRate(watchdog::tick,
                    watchdog.periodMs(), watchdog.periodMs(), TimeUnit.MILLISECONDS));
        }
        log.info("Проект {}: запущено фоновых задач {}, watchdog {}",
                projectId, definitions.tasksOrEmpty().size(), definitions.watchdog() != null);
    }

    void stop() {
        if (data != null) {
            data.stop();
        }
        futures.forEach(future -> future.cancel(false));
        futures.clear();
        context.tags().unwatch(watchedTags);
        watchedTags.clear();
    }

    void reloadData() {
        if (data != null) {
            data.reload();
        }
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
            log.warn("Проект {}: сохранённые переменные не прочитаны, беру значения по умолчанию: {}",
                    projectId, e.getMessage());
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
            log.warn("Проект {}, задача {}: память задачи не прочитана, чистый старт: {}",
                    projectId, task.id(), e.getMessage());
            return null;
        }
    }
}
