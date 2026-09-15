package com.example.scriptcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFunctionTest {

    private static SandboxExecutor executor;
    private static ProjectData data;

    @BeforeAll
    static void setUp() throws Exception {
        executor = new SandboxExecutor(1);
        data = ProjectData.parse(new ObjectMapper().readTree("""
                {"tables":[{"name":"solutions",
                  "columns":[{"name":"density","value_type":"float"},{"name":"meta","value_type":"json"}],
                  "rows":[{"key":"ALK","values":{"density":1.32,"meta":{"tanks":[1,2]}}}]}]}
                """));
    }

    @AfterAll
    static void tearDown() {
        executor.close();
    }

    /** Строка по ключу, вложенный json, все строки и null на неизвестный ключ — обычное чтение. */
    @Test
    void readsRowsByKeyAndAll() {
        assertEquals(1.32, run("result = data('solutions', 'ALK').density;"));
        assertEquals(2.0, run("result = data('solutions', 'ALK').meta.tanks[1];"));
        assertEquals(1.0, run("result = data('solutions').length;"));
        assertNull(run("result = data('solutions', 'NONE');"));
    }

    /** Опечатка в таблице или колонке падает с понятным текстом, а не превращается в undefined. */
    @Test
    void typosFail() {
        ScriptExecutionException table = assertThrows(ScriptExecutionException.class,
                () -> run("result = data('solutons');"));
        assertTrue(table.getMessage().contains("data(): неизвестная таблица 'solutons'"), table.getMessage());

        ScriptExecutionException column = assertThrows(ScriptExecutionException.class,
                () -> run("result = data('solutions', 'ALK').densty;"));
        assertTrue(column.getMessage().contains("неизвестная колонка 'densty' в таблице 'solutions'"), column.getMessage());
    }

    /** Снимок общий для всех скриптов: запись — ошибка, чтение целиком работает, исходник цел. */
    @Test
    void snapshotIsReadOnly() {
        ScriptExecutionException write = assertThrows(ScriptExecutionException.class,
                () -> run("data('solutions', 'ALK').density = 0;"));
        assertTrue(write.getMessage().contains("data(): данные проекта только для чтения"), write.getMessage());

        String json = (String) run("result = JSON.stringify(data('solutions', 'ALK'));");
        assertTrue(json.contains("\"density\":1.32"), json);
        assertEquals(1.32, data.table("solutions").rowsByKey().get("ALK").get("density"));
    }

    private Object run(String script) {
        return executor.run(script, Map.of("data", new DataFunction(data)), List.of("result"), 2000).get("result");
    }
}
