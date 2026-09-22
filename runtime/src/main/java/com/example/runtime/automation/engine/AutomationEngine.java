package com.example.runtime.automation.engine;

import com.example.runtime.automation.AutomationEngineProperties;
import com.example.runtime.automation.definition.ProjectDefinitions;
import com.example.runtime.automation.definition.TaskDefinition;
import com.example.runtime.automation.definition.VariableDefinition;
import com.example.runtime.automation.store.AutomationStore;
import com.example.scriptcore.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * Какие проекты исполняют фоновые задачи. Проект исполняет задачи, только когда выполнены оба
 * условия: он поднят в runtime и для него есть определения. События приходят из двух потоков —
 * реестра проектов и топика определений — в любом порядке, поэтому все методы под одним локом.
 * <p>
 * Потоки движка — свои: планировщик тактов, пул исполнения, поток загрузки данных проекта. Такт
 * регулятора не стоит в одной очереди с onChange, процедурами и отправкой кадров мониторам.
 */
@Slf4j
public class AutomationEngine {

    record Context(ScheduledExecutorService scheduler, ExecutorService workers, SandboxExecutor scripts,
                   TagCache tags, CommandSender commands, TaskObserver observer, AutomationStore store,
                   ObjectMapper mapper, ProjectDataFetcher dataFetcher, ScheduledExecutorService dataLoader,
                   long dataRetryMinMs, long dataRetryMaxMs) {
    }

    private final Context context;
    private final LongPredicate projectUp;
    private final Map<Long, ProjectDefinitions> definitions = new HashMap<>();
    private final Map<Long, ProjectAutomation> running = new HashMap<>();

    /**
     * @param projectUp поднят ли проект в этом runtime — он же признак действительности владения:
     *                  погашенный проект не пишет ни в ПЛК, ни в базу, даже если такт уже в очереди
     */
    public AutomationEngine(AutomationEngineProperties properties, TagCache tags, CommandSender commands,
                            TaskObserver observer, AutomationStore store, ObjectMapper mapper,
                            ProjectDataFetcher dataFetcher, LongPredicate projectUp) {
        int workers = Math.max(1, properties.getWorkerThreads());
        // Очередь ограничена, лишние такты отбрасываются: переполнение — это OVERRUN, а не растущая очередь.
        ThreadPoolExecutor workerPool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 16), daemon("automation-worker"),
                new ThreadPoolExecutor.DiscardPolicy());
        this.context = new Context(
                Executors.newScheduledThreadPool(2, daemon("automation-scheduler")),
                workerPool,
                new SandboxExecutor(properties.getContextPoolSize()),
                tags, commands, observer, store, mapper, dataFetcher,
                // Свой поток: HTTP к editor с таймаутом до секунд не должен задерживать такты.
                Executors.newSingleThreadScheduledExecutor(daemon("automation-data")),
                properties.getDataRetryMinMs(), properties.getDataRetryMaxMs());
        this.projectUp = projectUp;
    }

    /** Новая версия определений проекта; {@code null} — определения удалены (tombstone). */
    public synchronized void definitionsChanged(long projectId, ProjectDefinitions projectDefinitions) {
        // Те же определения ещё раз (переподключение потребителя догоняет топик заново) — задачи
        // не перезапускаются: перезапуск сбрасывал бы такт и писал контрольные точки впустую.
        if (projectDefinitions != null && running.containsKey(projectId)
                && projectDefinitions.equals(definitions.get(projectId))) {
            return;
        }
        stop(projectId);
        // После stop: его сброс уже унёс накопленное. Такт, начавшийся до stop, ещё может дописать
        // статус удалённой задачи — окно в длительность одного такта; такую строку уберёт следующая
        // смена набора или перезапуск (определения догоняются из топика заново).
        context.observer().retain(projectId, taskIdsOf(projectDefinitions), variableNamesOf(projectDefinitions));
        if (projectDefinitions == null) {
            definitions.remove(projectId);
            return;
        }
        definitions.put(projectId, projectDefinitions);
        if (projectUp.test(projectId)) {
            start(projectDefinitions);
        }
    }

    public synchronized void projectActivated(long projectId) {
        ProjectDefinitions projectDefinitions = definitions.get(projectId);
        if (projectDefinitions != null && !running.containsKey(projectId)) {
            start(projectDefinitions);
        }
    }

    public synchronized void projectDeactivated(long projectId) {
        stop(projectId);
    }

    public synchronized boolean isRunning(long projectId) {
        return running.containsKey(projectId);
    }

    /** @return значение переменной или {@code null}, если задачи проекта здесь не исполняются */
    public synchronized Object readVariable(long projectId, String name) {
        ProjectAutomation project = running.get(projectId);
        return project == null || project.variables() == null ? null : project.variables().get(name);
    }

    /**
     * Запись переменной не из задачи, а из процедуры рецепта (взвод аварий). Только объявленной:
     * опечатка в рецепте не должна заводить переменную, которую ни одна задача не читает. Значение
     * приводится к объявленному типу, как выход задачи, и сохраняется тем же путём, что {@code setVar}.
     */
    public synchronized boolean writeVariable(long projectId, String name, Object value) {
        ProjectAutomation project = running.get(projectId);
        String type = project == null ? null : project.declaredType(name);
        if (type == null || project.variables() == null) {
            return false;
        }
        Object typed;
        try {
            typed = ValueTypes.toOutput(value, type);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (project.variables().set(name, typed)) {
            context.observer().variable(projectId, name, typed);
        }
        return true;
    }

    /** @return {@code false}, если задачи проекта не исполняются этим экземпляром */
    public synchronized boolean reloadData(long projectId) {
        ProjectAutomation project = running.get(projectId);
        if (project == null) {
            return false;
        }
        project.reloadData();
        return true;
    }

    /** Вызывается контейнером: бин объявлен с {@code destroyMethod = "shutdown"} в AutomationBeans. */
    public synchronized void shutdown() {
        new ArrayList<>(running.keySet()).forEach(this::stop);
        context.scheduler().shutdownNow();
        context.workers().shutdownNow();
        context.dataLoader().shutdownNow();
        context.scripts().close();
    }

    private void start(ProjectDefinitions projectDefinitions) {
        long projectId = projectDefinitions.projectId();
        ProjectAutomation project = new ProjectAutomation(projectDefinitions, context, () -> projectUp.test(projectId));
        try {
            project.start();
            running.put(projectId, project);
            // Задачи публикуют переменную только при изменении: неизменная после перезапуска runtime
            // так и не дошла бы до монитора, и индикатор висел бы серым «нет данных». Пустая
            // (без значения по умолчанию и без сохранённого) остаётся неопубликованной — это честно.
            project.variables().snapshot().forEach((name, value) -> {
                if (value != null) {
                    context.observer().variable(projectId, name, value);
                }
            });
        } catch (Exception e) {
            project.stop();
            log.error("Проект {}: фоновые задачи не запущены: {}", projectId, e.getMessage(), e);
        }
    }

    private static Set<Long> taskIdsOf(ProjectDefinitions projectDefinitions) {
        if (projectDefinitions == null) {
            return Set.of();
        }
        return projectDefinitions.tasksOrEmpty().stream().map(TaskDefinition::id).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<String> variableNamesOf(ProjectDefinitions projectDefinitions) {
        if (projectDefinitions == null) {
            return Set.of();
        }
        return projectDefinitions.variablesOrEmpty().stream().map(VariableDefinition::name)
                .collect(Collectors.toSet());
    }

    /** Остановить задачи проекта и сбросить в базу всё накопленное по нему. */
    private void stop(long projectId) {
        ProjectAutomation project = running.remove(projectId);
        if (project == null) {
            return;
        }
        project.stop();
        context.observer().flushProject(projectId);
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
