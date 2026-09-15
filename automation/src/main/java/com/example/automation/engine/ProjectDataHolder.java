package com.example.automation.engine;

import com.example.scriptcore.ProjectData;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Таблицы данных одного проекта. Загрузка идёт на отдельном потоке — не под локом
 * {@link ProjectRegistry} и не на планировщике тактов: зависший editor не задерживает такты.
 * <p>
 * Пока загрузки не было, {@link #current()} — {@code null}, и {@code data()} в задаче даёт ошибку.
 * Первая загрузка повторяется с растущей паузой; после успеха запросов нет до {@link #reload()}.
 * Неудачное перечитывание оставляет прежний снимок.
 */
@Slf4j
final class ProjectDataHolder {

    private final long projectId;
    private final ProjectDataFetcher fetcher;
    private final ScheduledExecutorService loader;
    private final long retryMinMs;
    private final long retryMaxMs;
    private final AtomicReference<ProjectData> data = new AtomicReference<>();

    private ScheduledFuture<?> pending;
    private long nextDelayMs;
    private boolean stopped;

    ProjectDataHolder(long projectId, ProjectDataFetcher fetcher, ScheduledExecutorService loader,
                      long retryMinMs, long retryMaxMs) {
        this.projectId = projectId;
        this.fetcher = fetcher;
        this.loader = loader;
        this.retryMinMs = retryMinMs;
        this.retryMaxMs = retryMaxMs;
        this.nextDelayMs = retryMinMs;
    }

    ProjectData current() {
        return data.get();
    }

    void start() {
        schedule(0);
    }

    /** Перечитать сейчас, не дожидаясь паузы повтора. */
    synchronized void reload() {
        nextDelayMs = retryMinMs;
        schedule(0);
    }

    synchronized void stop() {
        stopped = true;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private synchronized void schedule(long delayMs) {
        if (stopped) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        pending = loader.schedule(this::load, delayMs, TimeUnit.MILLISECONDS);
    }

    private void load() {
        try {
            data.set(fetcher.fetch(projectId));
            synchronized (this) {
                nextDelayMs = retryMinMs;
            }
            log.info("Project {}: project data loaded", projectId);
        } catch (Exception e) {
            if (data.get() != null) {
                log.warn("Project {}: project data not reloaded, keeping previous: {}", projectId, e.getMessage());
                return;
            }
            long delay;
            synchronized (this) {
                delay = nextDelayMs;
                nextDelayMs = Math.min(retryMaxMs, nextDelayMs * 2);
            }
            log.warn("Project {}: project data not loaded, retry in {} ms: {}", projectId, delay, e.getMessage());
            schedule(delay);
        }
    }
}
