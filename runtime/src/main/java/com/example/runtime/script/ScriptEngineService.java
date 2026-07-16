package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Компилятор/исполнитель простых скриптов (в основном if/else, меняющих значения
 * свойств компонента) на GraalVM JavaScript.
 * <p>
 * Триггеры выполнения (см. {@code TagValueRouter} и {@code RuntimeSessionService}):
 * <ul>
 *   <li>{@code onChange} свойства — только когда меняется тег, к которому оно привязано;</li>
 *   <li>{@code Script} компонента — только по действию с фронта (ACTION).</li>
 * </ul>
 * Скрипту доступны переменные {@code tag} (новое значение тега, только для onChange)
 * и {@code props} — мутируемый объект текущих значений свойств компонента по имени;
 * изменения {@code props.xxx = ...} после выполнения превращаются в PROPERTY_UPDATE.
 */
@Service
@Slf4j
public class ScriptEngineService {

    private final Map<String, Source> sourceCache = new ConcurrentHashMap<>();
    private final BlockingQueue<Context> pool;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "script-watchdog"));
    private final ExecutorService executor;
    private final long timeoutMs;

    public ScriptEngineService(RuntimeProperties properties) {
        int poolSize = Math.max(1, properties.getScript().getContextPoolSize());
        this.timeoutMs = properties.getScript().getExecutionTimeoutMs();
        this.pool = new ArrayBlockingQueue<>(poolSize);
        this.executor = Executors.newFixedThreadPool(poolSize, r -> new Thread(r, "script-exec"));
    }

    @PostConstruct
    void initPool() {
        int size = pool.remainingCapacity();
        for (int i = 0; i < size; i++) {
            pool.add(newContext());
        }
        log.info("ScriptEngineService: GraalVM JS context pool initialized, size={}", size);
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        executor.shutdownNow();
        pool.forEach(Context::close);
    }

    private Context newContext() {
        return Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    /**
     * Выполняет onChange свойства при изменении привязанного тега.
     * Возвращает мутированную копию {@code props} — вызывающий сам вычисляет diff.
     */
    public Map<String, Object> runOnChange(String scriptSource, Object tagValue, Map<String, Object> props) {
        return execute(scriptSource, tagValue, props);
    }

    /** Выполняет компонентный Script по действию с фронта (нажатие кнопки и т.п.). */
    public Map<String, Object> runAction(String scriptSource, Map<String, Object> props) {
        return execute(scriptSource, null, props);
    }

    private Map<String, Object> execute(String scriptSource, Object tagValue, Map<String, Object> props) {
        if (scriptSource == null || scriptSource.isBlank()) {
            return props;
        }
        Source source = sourceCache.computeIfAbsent(scriptSource,
                s -> Source.create("js", s));

        Context ctx = borrow();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?> future = executor.submit(() -> {
            try {
                ctx.getBindings("js").putMember("tag", tagValue);
                ctx.getBindings("js").putMember("props", new MapProxyObject(props));
                ctx.eval(source);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        ScheduledFuture<?> cancelTask = watchdog.schedule(() -> {
            if (!future.isDone()) {
                log.warn("Script execution exceeded {} ms, cancelling context", timeoutMs);
                try {
                    ctx.close(true);
                } catch (Exception ignored) {
                    // context might already be closing
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            future.get(timeoutMs + 100, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Script execution failed/timed out: {}", e.getMessage());
            replace(ctx);
            throw new ScriptExecutionException("Script execution failed or timed out", e);
        } finally {
            cancelTask.cancel(false);
        }

        Throwable t = failure.get();
        if (t != null) {
            boolean cancelled = t instanceof PolyglotException pe && pe.isCancelled();
            if (cancelled) {
                replace(ctx);
            } else {
                release(ctx);
            }
            throw new ScriptExecutionException("Script execution error: " + t.getMessage(), t);
        }

        release(ctx);
        return props;
    }

    private Context borrow() {
        try {
            Context ctx = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (ctx == null) {
                log.warn("Script context pool exhausted, creating a temporary context");
                return newContext();
            }
            return ctx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptExecutionException("Interrupted while waiting for a script context", e);
        }
    }

    private void release(Context ctx) {
        if (!pool.offer(ctx)) {
            ctx.close();
        }
    }

    private void replace(Context ctx) {
        try {
            ctx.close(true);
        } catch (Exception ignored) {
        }
        pool.offer(newContext());
    }
}
