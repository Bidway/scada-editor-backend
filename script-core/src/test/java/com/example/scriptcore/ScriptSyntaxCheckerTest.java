package com.example.scriptcore;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSyntaxCheckerTest {

    /** Скрипт задачи — тело функции: ранний return на верхнем уровне обязан проходить. */
    @Test
    void acceptsTopLevelReturn() {
        try (ScriptSyntaxChecker checker = new ScriptSyntaxChecker()) {
            assertTrue(checker.check("if (inputs.Man) { return; }\nwrite('U', 1);").isEmpty());
        }
    }

    /** Номер строки — в тексте пользователя, без строки обёртки. */
    @Test
    void reportsSyntaxErrorWithUserLine() {
        try (ScriptSyntaxChecker checker = new ScriptSyntaxChecker()) {
            Optional<ScriptSyntaxChecker.SyntaxError> error = checker.check("const a = 1;\nconst = 2;");

            assertTrue(error.isPresent());
            assertEquals(2, error.get().line());
        }
    }
}
