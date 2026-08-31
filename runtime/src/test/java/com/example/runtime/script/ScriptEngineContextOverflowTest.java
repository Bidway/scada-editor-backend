package com.example.runtime.script;

import com.example.runtime.config.RuntimeProperties;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * scada-3pz: при исчерпании пула и overflow-permit'ов {@code borrow()} обязан отказать,
 * а не создавать temporary-контексты без ограничения сверху.
 * <p>
 * scada-8cp: у ACTION есть маленький отдельный резерв, недоступный onChange, — проверяем,
 * что приоритет реально работает в обе стороны (ACTION им пользуется, onChange — нет).
 * <p>
 * Гонять это через реальный busy-loop и watchdog ненадёжно: cancel+{@code replace()}
 * occupier'а нередко успевает вернуть контекст в очередь раньше, чем истекает {@code poll()}
 * второго вызова — ложно маскирует отсутствие лимита. Вместо этого пул/резерв дренируются
 * напрямую рефлексией: нужные сценарии получаются детерминированно, без потоков и watchdog.
 */
class ScriptEngineContextOverflowTest {

    private ScriptEngineService engine;

    @BeforeEach
    void setUp() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        properties.getScript().setMaxOverflowContexts(0);
        properties.getScript().setActionReservePoolSize(1);
        properties.getScript().setExecutionTimeoutMs(50L);
        engine = new ScriptEngineService(properties);
        engine.initPool();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    @DisplayName("пул(1) + overflow(0), onChange: пул пуст — borrow() отказывает, а не плодит temporary-контексты")
    void onChangeBorrowFailsWhenPoolAndOverflowExhausted() throws Exception {
        drainPool(engine);

        assertThatThrownBy(() -> borrow(engine, false))
                .as("пул пуст, overflow permit'ов нет — borrow() не должен создавать безлимитный temporary-контекст")
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    @DisplayName("пул(1) + overflow(0), onChange: резерв ACTION onChange недоступен вовсе")
    void onChangeCannotUseActionReserve() throws Exception {
        drainPool(engine); // общий пул пуст, а резерв ACTION как был полон — onChange его не видит

        assertThatThrownBy(() -> borrow(engine, false))
                .as("у резерва ACTION есть свободный контекст, но onChange не имеет к нему доступа")
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    @DisplayName("пул(1) + overflow(0), ACTION: пустой пул не мешает — резерв берёт приоритет")
    void actionUsesReserveWhenPoolExhausted() throws Exception {
        drainPool(engine); // общий пул пуст; резерв ACTION (size=1) — ещё полон

        Object borrowed = borrow(engine, true);
        try {
            assertThat(contextOf(borrowed)).isNotNull();
        } finally {
            close(borrowed, engine);
        }
    }

    @Test
    @DisplayName("пул(1) + overflow(0), ACTION: пул и резерв оба пусты — тоже отказывает")
    void actionFailsWhenPoolAndReserveAndOverflowExhausted() throws Exception {
        drainPool(engine);
        drainActionReserve(engine);

        assertThatThrownBy(() -> borrow(engine, true))
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    @DisplayName("пул(1) + overflow(1): один overflow-контекст создать можно, второй подряд — уже нет")
    void secondConcurrentOverflowFailsAfterFirstSucceeds() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        properties.getScript().setMaxOverflowContexts(1);
        properties.getScript().setActionReservePoolSize(1);
        properties.getScript().setExecutionTimeoutMs(50L);
        ScriptEngineService overflowEngine = new ScriptEngineService(properties);
        overflowEngine.initPool();
        try {
            drainPool(overflowEngine);

            // Пул пуст, но maxOverflowContexts=1 — один temporary-контекст создать ещё можно.
            Object firstOverflow = borrow(overflowEngine, false);
            try {
                assertThat(contextOf(firstOverflow)).isNotNull();

                // Второй одновременный temporary-контекст сверх единственного overflow-permit'а —
                // это и есть сценарий scada-3pz без лимита; с лимитом обязан отказать.
                assertThatThrownBy(() -> borrow(overflowEngine, false))
                        .isInstanceOf(ScriptExecutionException.class)
                        .hasMessageContaining("overloaded");
            } finally {
                close(firstOverflow, overflowEngine);
            }
        } finally {
            overflowEngine.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private void drainPool(ScriptEngineService target) throws Exception {
        drainQueue(target, "pool");
    }

    @SuppressWarnings("unchecked")
    private void drainActionReserve(ScriptEngineService target) throws Exception {
        drainQueue(target, "actionReserve");
    }

    @SuppressWarnings("unchecked")
    private void drainQueue(ScriptEngineService target, String fieldName) throws Exception {
        Field field = ScriptEngineService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        BlockingQueue<Context> queue = (BlockingQueue<Context>) field.get(target);
        Context ctx;
        while ((ctx = queue.poll()) != null) {
            ctx.close();
        }
    }

    /** Вызывает приватный {@code borrow(boolean)}, возвращает приватный record {@code Borrowed}. */
    private Object borrow(ScriptEngineService target, boolean forAction) throws Exception {
        Method borrowMethod = ScriptEngineService.class.getDeclaredMethod("borrow", boolean.class);
        borrowMethod.setAccessible(true);
        try {
            return borrowMethod.invoke(target, forAction);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** Достаёт {@code Context} из приватного record {@code Borrowed} через его accessor. */
    private Context contextOf(Object borrowed) throws Exception {
        Method ctxAccessor = borrowed.getClass().getDeclaredMethod("ctx");
        ctxAccessor.setAccessible(true);
        return (Context) ctxAccessor.invoke(borrowed);
    }

    /**
     * Прогоняет одолженный контекст через приватный {@code release(Borrowed)} — штатный путь
     * возврата (в свою очередь, если есть место, иначе закрывается сам и отдаёт permit).
     * Закрывать {@code Context} отдельно после этого нельзя: если возврат в очередь удался,
     * контекст остался живым и будет закрыт позже — самим {@code engine.shutdown()}.
     */
    private void close(Object borrowed, ScriptEngineService target) throws Exception {
        Method releaseMethod = ScriptEngineService.class.getDeclaredMethod("release", borrowed.getClass());
        releaseMethod.setAccessible(true);
        releaseMethod.invoke(target, borrowed);
    }
}
