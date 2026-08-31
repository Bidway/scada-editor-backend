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
 * Гонять это через реальный busy-loop и watchdog ненадёжно: cancel+{@code replace()}
 * occupier'а нередко успевает вернуть в пул свежий контекст раньше, чем истекает
 * {@code poll()} второго вызова — ложно маскирует отсутствие лимита. Вместо этого пул
 * дренируется напрямую рефлексией: сценарий «пул пуст, overflow исчерпан» получается
 * детерминированно, без потоков и без watchdog в игре вовсе.
 */
class ScriptEngineContextOverflowTest {

    private ScriptEngineService engine;

    @BeforeEach
    void setUp() {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        properties.getScript().setMaxOverflowContexts(0);
        properties.getScript().setExecutionTimeoutMs(50L);
        engine = new ScriptEngineService(properties);
        engine.initPool();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
    }

    @Test
    @DisplayName("пул(1) + overflow(0): пул пуст, permit'ов нет — borrow() отказывает, а не плодит temporary-контексты")
    void borrowFailsWhenPoolAndOverflowExhausted() throws Exception {
        drainPool();

        assertThatThrownBy(this::borrow)
                .as("пул пуст, overflow permit'ов нет — borrow() не должен создавать безлимитный temporary-контекст")
                .isInstanceOf(ScriptExecutionException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    @DisplayName("пул(1) + overflow(0): один overflow-контекст создать можно, второй подряд — уже нет")
    void secondConcurrentOverflowFailsAfterFirstSucceeds() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getScript().setContextPoolSize(1);
        properties.getScript().setOnChangeThreads(1);
        properties.getScript().setMaxOverflowContexts(1);
        properties.getScript().setExecutionTimeoutMs(50L);
        ScriptEngineService overflowEngine = new ScriptEngineService(properties);
        overflowEngine.initPool();
        try {
            drainPool(overflowEngine);

            // Пул пуст, но maxOverflowContexts=1 — один temporary-контекст создать ещё можно.
            Context firstOverflow = (Context) borrow(overflowEngine);
            try {
                assertThat(firstOverflow).isNotNull();

                // Второй одновременный temporary-контекст сверх единственного overflow-permit'а —
                // это и есть сценарий scada-3pz без лимита; с лимитом обязан отказать.
                assertThatThrownBy(() -> borrow(overflowEngine))
                        .isInstanceOf(ScriptExecutionException.class)
                        .hasMessageContaining("overloaded");
            } finally {
                firstOverflow.close();
            }
        } finally {
            overflowEngine.shutdown();
        }
    }

    private void drainPool() throws Exception {
        drainPool(engine);
    }

    @SuppressWarnings("unchecked")
    private void drainPool(ScriptEngineService target) throws Exception {
        Field poolField = ScriptEngineService.class.getDeclaredField("pool");
        poolField.setAccessible(true);
        BlockingQueue<Context> pool = (BlockingQueue<Context>) poolField.get(target);
        Context ctx;
        while ((ctx = pool.poll()) != null) {
            ctx.close();
        }
    }

    private Object borrow() throws Exception {
        return borrow(engine);
    }

    private Object borrow(ScriptEngineService target) throws Exception {
        Method borrowMethod = ScriptEngineService.class.getDeclaredMethod("borrow");
        borrowMethod.setAccessible(true);
        try {
            return borrowMethod.invoke(target);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
