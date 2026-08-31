package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
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
 * Плюс функция {@code writeTag(propertyName, value)} — запись тега в ПЛК
 * (см. {@link TagWriteSink}); сама отправка происходит уже вне движка.
 */
@Service
@Slf4j
public class ScriptEngineService {

    /** Правки скрипта в редакторе создают новые ключи (ключ = весь текст), поэтому кэш ограничен. */
    private static final int MAX_CACHED_SOURCES = 512;

    /** LRU с ограничением: без предела кэш рос бы навсегда (#11). Синхронизирован — доступ из script-exec потоков. */
    private final Map<String, Source> sourceCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
                    return size() > MAX_CACHED_SOURCES;
                }
            });
    private final BlockingQueue<Context> pool;
    /**
     * Маленький резерв контекстов, который видит только ACTION (scada-8cp). ACTION редки
     * относительно onChange — полноценный второй пул простаивал бы без нужды, поэтому общий
     * {@code pool} остаётся единым и максимально утилизированным для обоих видов, а сюда ACTION
     * заглядывает первым делом, чтобы не голодать при захвате общего пула всплеском onChange.
     * onChange про этот резерв не знает вовсе.
     */
    private final BlockingQueue<Context> actionReserve;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "script-watchdog"));
    private final ExecutorService executor;
    private final long timeoutMs;
    /**
     * Верхний предел общего числа живых Graal-контекстов (общий пул + резерв ACTION +
     * временные), scada-3pz. {@code borrow()} вызывается на потоке КАЖДОГО вызывающего (полосы
     * OnChangeDispatcher, запросы ACTION), а не на {@code executor} — тот ограничивает только
     * параллелизм исполнения уже одолженного контекста, а не число одновременных попыток
     * создать временный при исчерпании пула. Permit берётся при любом {@code newContext()} (тут
     * же в {@code initPool}, в {@code borrow} на overflow, в {@code replace} на замену) и
     * освобождается ровно при фактическом {@code ctx.close()} — не при возврате в пул/резерв.
     */
    private final Semaphore contextSlots;

    /** Одолженный контекст вместе с очередью, в которую его надо вернуть (scada-8cp). */
    private record Borrowed(Context ctx, BlockingQueue<Context> home) {
    }

    public ScriptEngineService(RuntimeProperties properties) {
        // Контекстов должно хватать на всех одновременных вызывающих, иначе поток ждёт
        // на poll() весь execution-timeout, а затем создаёт временный контекст с полным
        // прогревом — сотни миллисекунд на горячем пути, и без ограничения сверху.
        // Основной источник параллелизма — полосы OnChangeDispatcher, поэтому размер
        // пула не может быть меньше их числа: две настройки иначе молча разъезжаются.
        int poolSize = Math.max(
                Math.max(1, properties.getScript().getContextPoolSize()),
                properties.getScript().getOnChangeThreads());
        int reserveSize = Math.max(0, properties.getScript().getActionReservePoolSize());
        this.timeoutMs = properties.getScript().getExecutionTimeoutMs();
        this.pool = new ArrayBlockingQueue<>(poolSize);
        this.actionReserve = new ArrayBlockingQueue<>(Math.max(1, reserveSize));
        // Резерв тоже сидит на script-exec: он про то, у какого контекста приоритет, а не про
        // отдельный параллелизм исполнения — потоки дешевле контекстов (см. contextSlots).
        this.executor = Executors.newFixedThreadPool(poolSize, r -> new Thread(r, "script-exec"));
        int maxOverflow = Math.max(0, properties.getScript().getMaxOverflowContexts());
        this.contextSlots = new Semaphore(poolSize + reserveSize + maxOverflow);
    }

    @PostConstruct
    void initPool() {
        int size = pool.remainingCapacity();
        for (int i = 0; i < size; i++) {
            contextSlots.acquireUninterruptibly();
            Context ctx = newContext();
            warmUp(ctx);
            pool.add(ctx);
        }
        int reserveSize = actionReserve.remainingCapacity();
        for (int i = 0; i < reserveSize; i++) {
            contextSlots.acquireUninterruptibly();
            Context ctx = newContext();
            warmUp(ctx);
            actionReserve.add(ctx);
        }
        log.info("ScriptEngineService: GraalVM JS context pool initialized and warmed, size={}, "
                + "actionReserve={}", size, reserveSize);
    }

    /**
     * Первый {@code eval} на свежем контексте инициализирует движок GraalVM и не
     * укладывается в {@link #timeoutMs} — из-за чего первый реальный onChange/ACTION
     * гиб бы по watchdog. Прогоняем разовый eval с теми же биндингами при старте,
     * чтобы эту стоимость оплатить на буте, а не на горячем пути.
     */
    private void warmUp(Context ctx) {
        try {
            ctx.getBindings("js").putMember("tag", 0);
            ctx.getBindings("js").putMember("props", new MapProxyObject(new HashMap<>()));
            ctx.getBindings("js").putMember("writeTag", writeTagFunction(TagWriteSink.NOOP));
            ctx.eval(Source.create("js",
                    "typeof tag; typeof props; props.__warm = tag; writeTag('__warm', tag);"));
        } catch (Exception e) {
            log.warn("Script context warm-up failed (continuing): {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        executor.shutdownNow();
        pool.forEach(Context::close);
        actionReserve.forEach(Context::close);
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
    public Map<String, Object> runOnChange(String scriptSource, Object tagValue, Map<String, Object> props,
                                           TagWriteSink writeSink) {
        return execute(scriptSource, tagValue, props, writeSink, false);
    }

    /** Выполняет компонентный Script по действию с фронта (нажатие кнопки и т.п.). */
    public Map<String, Object> runAction(String scriptSource, Map<String, Object> props, TagWriteSink writeSink) {
        return execute(scriptSource, null, props, writeSink, true);
    }

    private Map<String, Object> execute(String scriptSource, Object tagValue, Map<String, Object> props,
                                        TagWriteSink writeSink, boolean forAction) {
        if (scriptSource == null || scriptSource.isBlank()) {
            return props;
        }
        Source source = sourceCache.computeIfAbsent(scriptSource,
                s -> Source.create("js", s));

        TagWriteSink sink = writeSink != null ? writeSink : TagWriteSink.NOOP;
        Borrowed borrowed = borrow(forAction);
        Context ctx = borrowed.ctx();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?> future = executor.submit(() -> {
            try {
                ctx.getBindings("js").putMember("tag", tagValue);
                ctx.getBindings("js").putMember("props", new MapProxyObject(props));
                ctx.getBindings("js").putMember("writeTag", writeTagFunction(sink));
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
            // Прекращаем зависший script-exec поток: контекст уже закрывается из-под него.
            future.cancel(true);
            replace(borrowed);
            throw new ScriptExecutionException("Script execution failed or timed out", e);
        } finally {
            cancelTask.cancel(false);
        }

        Throwable t = failure.get();
        if (t != null) {
            boolean cancelled = t instanceof PolyglotException pe && pe.isCancelled();
            if (cancelled) {
                replace(borrowed);
            } else {
                release(borrowed);
            }
            throw new ScriptExecutionException("Script execution error: " + t.getMessage(), t);
        }

        release(borrowed);
        return props;
    }

    /**
     * Функция {@code writeTag(propertyName, value)}, видимая скрипту.
     * <p>
     * {@link ProxyExecutable} — единственный способ дать скрипту вызываемую функцию,
     * не открывая доступ к Java: {@code HostAccess.NONE} остаётся в силе, наружу
     * уходят только примитивы. Значение приводится к Java-типу здесь же, потому что
     * {@link Value} живёт лишь пока жив контекст, а команда отправляется позже.
     */
    private ProxyExecutable writeTagFunction(TagWriteSink sink) {
        return arguments -> {
            if (arguments.length < 2) {
                log.warn("writeTag() called with {} argument(s), expected 2 (propertyName, value)", arguments.length);
                return null;
            }
            Value nameArg = arguments[0];
            if (nameArg == null || !nameArg.isString()) {
                log.warn("writeTag(): first argument must be a property name string");
                return null;
            }
            sink.write(nameArg.asString(), toJavaValue(arguments[1]));
            return null;
        };
    }

    /** Значение из JS в контекстонезависимый Java-объект (единый конвертер — см. {@link GraalValues}). */
    private Object toJavaValue(Value value) {
        return GraalValues.toJava(value);
    }

    private Borrowed borrow(boolean forAction) {
        try {
            if (forAction) {
                // Резерв — не блокируясь: если там пусто, ACTION не ждёт его отдельно, а сразу
                // идёт в общий пул наравне с onChange (ниже).
                Context reserved = actionReserve.poll();
                if (reserved != null) {
                    return new Borrowed(reserved, actionReserve);
                }
            }
            Context ctx = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (ctx == null) {
                // Overflow-permit уже ждали timeoutMs на poll() выше — дальше не блокируемся,
                // отказываем сразу: копить вызывающих в очереди за permit'ом ничем не лучше
                // неограниченного роста контекстов, которого это и призвано не допустить.
                if (!contextSlots.tryAcquire()) {
                    throw new ScriptExecutionException(
                            "Script engine overloaded: context pool and overflow both exhausted", null);
                }
                log.warn("Script context pool exhausted, creating a temporary context");
                Context fresh = newContext();
                warmUp(fresh);
                return new Borrowed(fresh, pool);
            }
            return new Borrowed(ctx, pool);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScriptExecutionException("Interrupted while waiting for a script context", e);
        }
    }

    private void release(Borrowed borrowed) {
        if (!borrowed.home().offer(borrowed.ctx())) {
            borrowed.ctx().close();
            contextSlots.release();
        }
    }

    private void replace(Borrowed borrowed) {
        try {
            borrowed.ctx().close(true);
        } catch (Exception ignored) {
        }
        contextSlots.release();
        // Обязательно прогреть: иначе холодный контекст в пуле/резерве → следующий eval снова
        // таймаутит и снова кладёт холодный, и так по кругу (самоподдерживающийся сбой, #3).
        // Возвращаем в ТУ ЖЕ очередь, откуда одолжили, — иначе резерв ACTION со временем
        // вытечет в общий пул при каждой отмене по watchdog.
        contextSlots.acquireUninterruptibly();
        Context fresh = newContext();
        warmUp(fresh);
        borrowed.home().offer(fresh);
    }
}
