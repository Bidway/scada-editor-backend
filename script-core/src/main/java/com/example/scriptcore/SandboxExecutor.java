package com.example.scriptcore;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Исполнитель скриптов фоновых задач: пул прогретых контекстов GraalVM, таймаут на вызов,
 * песочница без доступа к Java.
 * <p>
 * Контекст не потокобезопасен, поэтому один вызов — один одолженный контекст. Таймаут снимает
 * скрипт закрытием контекста ({@code close(true)}); такой контекст в пул не возвращается,
 * вместо него создаётся и прогревается новый — иначе следующий вызов снова упёрся бы в холодный
 * движок.
 */
public final class SandboxExecutor implements AutoCloseable {

    /** Правка скрипта даёт новый ключ (ключ — весь текст), поэтому кэш ограничен. */
    private static final int MAX_CACHED_SOURCES = 512;

    private final Map<String, Source> sources = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
                    return size() > MAX_CACHED_SOURCES;
                }
            });
    private final BlockingQueue<Context> pool;
    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;

    public SandboxExecutor(int poolSize) {
        int size = Math.max(1, poolSize);
        this.pool = new ArrayBlockingQueue<>(size);
        this.executor = Executors.newFixedThreadPool(size, daemon("sandbox-exec"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemon("sandbox-watchdog"));
        for (int i = 0; i < size; i++) {
            pool.add(newWarmContext());
        }
    }

    public Map<String, Object> run(String taskScript, Map<String, Object> bindings,
                                   List<String> readBack, long timeoutMs) {
        Source source = sources.computeIfAbsent(taskScript == null ? "" : taskScript,
                s -> Source.create("js", TaskScriptSource.wrap(s)));
        Context ctx = borrow(timeoutMs);
        Future<Map<String, Object>> future = executor.submit(() -> {
            Value globals = ctx.getBindings("js");
            try {
                bindings.forEach(globals::putMember);
                ctx.eval(source).executeVoid();
                Map<String, Object> result = new HashMap<>();
                for (String name : readBack) {
                    result.put(name, GraalValues.toJava(globals.getMember(name)));
                }
                return result;
            } finally {
                // Следующий вызов на этом контексте не должен увидеть прокси чужой задачи.
                for (String name : bindings.keySet()) {
                    globals.putMember(name, null);
                }
            }
        });
        ScheduledFuture<?> cancel = watchdog.schedule(() -> {
            if (!future.isDone()) {
                closeQuietly(ctx);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            Map<String, Object> result = future.get(timeoutMs + 100, TimeUnit.MILLISECONDS);
            pool.offer(ctx);
            return result;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            boolean cancelled = cause instanceof PolyglotException pe && pe.isCancelled();
            if (cancelled) {
                replace(ctx);
                throw new ScriptExecutionException("Script timed out after " + timeoutMs + " ms", cause);
            }
            pool.offer(ctx);
            throw new ScriptExecutionException("Script error: " + cause.getMessage(), cause);
        } catch (TimeoutException | InterruptedException e) {
            future.cancel(true);
            replace(ctx);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ScriptExecutionException("Script timed out after " + timeoutMs + " ms", e);
        } finally {
            cancel.cancel(false);
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        executor.shutdownNow();
        pool.forEach(SandboxExecutor::closeQuietly);
    }

    private Context borrow(long timeoutMs) {
        try {
            Context ctx = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (ctx == null) {
                throw new ScriptExecutionException("Script engine overloaded: no free context in " + timeoutMs + " ms", null);
            }
            return ctx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptExecutionException("Interrupted while waiting for a script context", e);
        }
    }

    private void replace(Context broken) {
        closeQuietly(broken);
        pool.offer(newWarmContext());
    }

    /** Первый eval на свежем контексте инициализирует движок и не укладывается в таймаут такта. */
    private static Context newWarmContext() {
        Context ctx = Context.newBuilder("js")
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.NONE)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        ctx.eval("js", "(function(){ var warm = { a: [1, 2] }; return warm.a.length; })()");
        return ctx;
    }

    private static void closeQuietly(Context ctx) {
        try {
            ctx.close(true);
        } catch (Exception ignored) {
            // контекст уже закрывается
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
