package com.example.automation.engine;

import com.example.automation.config.AutomationProperties;
import com.example.automation.definition.ProjectDefinitions;
import com.example.automation.kafka.OwnershipGuard;
import com.example.automation.store.AutomationStore;
import com.example.scriptcore.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Какие проекты исполняет экземпляр. Все изменения — из потока владения, под одним локом:
 * получение и потеря партиций, загрузка определений, их изменения.
 */
@Component
@Slf4j
public class ProjectRegistry {

    private final EngineContext context;
    private final Map<Long, ProjectRuntime> running = new HashMap<>();
    private final Map<Integer, Long> epochs = new HashMap<>();

    public ProjectRegistry(AutomationProperties properties, TagCache tags, CommandSender commands,
                           TaskObserver observer, OwnershipGuard guard, AutomationStore store, ObjectMapper mapper,
                           ProjectDataFetcher dataFetcher) {
        int workers = Math.max(1, properties.getEngine().getWorkerThreads());
        // Очередь ограничена, лишние такты отбрасываются: переполнение — это OVERRUN, а не растущая очередь.
        ThreadPoolExecutor workerPool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 16), daemon("automation-worker"),
                new ThreadPoolExecutor.DiscardPolicy());
        this.context = new EngineContext(
                Executors.newScheduledThreadPool(2, daemon("automation-scheduler")),
                workerPool,
                new SandboxExecutor(properties.getEngine().getContextPoolSize()),
                tags, commands, observer, guard, store, mapper,
                dataFetcher,
                // Свой поток: HTTP к editor с таймаутом до секунд не должен задерживать такты.
                Executors.newSingleThreadScheduledExecutor(daemon("automation-data")),
                properties.getEngine().getDataRetryMinMs(),
                properties.getEngine().getDataRetryMaxMs());
    }

    public synchronized void assign(int partition, long epoch) {
        epochs.put(partition, epoch);
    }

    public synchronized void activate(int partition, Collection<ProjectDefinitions> projects) {
        projects.forEach(definitions -> start(partition, definitions));
    }

    /** Новая версия определений проекта (или tombstone — {@code null}) в уже загруженной партиции. */
    public synchronized void apply(int partition, long projectId, ProjectDefinitions definitions) {
        stop(projectId, true);
        if (definitions != null && epochs.containsKey(partition)) {
            start(partition, definitions);
        }
    }

    /** @param lost партиция потеряна без штатного отзыва — состояние не сбрасываем, epoch уже не наш */
    public synchronized void revoke(Collection<Integer> partitions, boolean lost) {
        List<Long> projects = new ArrayList<>();
        running.forEach((projectId, runtime) -> {
            if (partitions.contains(runtime.partition())) {
                projects.add(projectId);
            }
        });
        projects.forEach(projectId -> stop(projectId, !lost));
        partitions.forEach(epochs::remove);
    }

    public synchronized void revokeAll(boolean lost) {
        revoke(new ArrayList<>(epochs.keySet()), lost);
    }

    public synchronized int ownedPartitions() {
        return epochs.size();
    }

    public synchronized int runningProjects() {
        return running.size();
    }

    /**
     * Перечитать данные проекта из editor. Под локом только поиск проекта — сама загрузка уходит
     * на поток данных.
     *
     * @return {@code false}, если проект не исполняется этим экземпляром
     */
    public synchronized boolean reloadData(long projectId) {
        ProjectRuntime runtime = running.get(projectId);
        if (runtime == null) {
            return false;
        }
        runtime.reloadData();
        return true;
    }

    @PreDestroy
    void shutdown() {
        revokeAll(false);
        context.scheduler().shutdownNow();
        context.workers().shutdownNow();
        context.dataLoader().shutdownNow();
        context.scripts().close();
    }

    private void start(int partition, ProjectDefinitions definitions) {
        if (definitions.projectId() == null || !epochs.containsKey(partition)) {
            return;
        }
        ProjectRuntime runtime = new ProjectRuntime(partition, epochs.get(partition), definitions, context);
        try {
            runtime.start();
            running.put(definitions.projectId(), runtime);
        } catch (Exception e) {
            runtime.stop();
            log.error("Project {} not started: {}", definitions.projectId(), e.getMessage(), e);
        }
    }

    private void stop(long projectId, boolean flush) {
        ProjectRuntime runtime = running.remove(projectId);
        if (runtime == null) {
            return;
        }
        runtime.stop();
        if (flush) {
            context.observer().flushProject(projectId);
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread thread = new Thread(r, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
