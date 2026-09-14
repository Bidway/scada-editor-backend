package com.example.scriptcore;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxExecutorTest {

    /** Регулятор переприсваивает state целиком — исполнитель обязан вернуть новое значение. */
    @Test
    void returnsReassignedState() {
        try (SandboxExecutor executor = new SandboxExecutor(1)) {
            Map<String, Object> state = new HashMap<>(Map.of("n", 1.0));

            Map<String, Object> result = executor.run("state = { n: state.n + 1 };",
                    Map.of("state", new MapProxyObject(state)), List.of("state"), 1000);

            assertEquals(Map.of("n", 2.0), result.get("state"));
        }
    }

    /** Зациклившийся скрипт снимается по таймауту, и пул после этого снова работает. */
    @Test
    void cancelsRunawayScriptAndRecovers() {
        try (SandboxExecutor executor = new SandboxExecutor(1)) {
            assertThrows(ScriptExecutionException.class,
                    () -> executor.run("while (true) {}", Map.of(), List.of(), 200));

            assertEquals(1.0, executor.run("x = 1;", Map.of(), List.of("x"), 1000).get("x"));
        }
    }
}
