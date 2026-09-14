package com.example.automation.definition;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Хэш того, от чего зависит смысл памяти задачи: скрипт, входы, выходы. Не совпал —
 * контрольная точка не восстанавливается: старая память под новый скрипт опаснее чистого старта.
 */
public final class DefinitionHash {

    private DefinitionHash() {
    }

    public static String of(ObjectMapper mapper, TaskDefinition task) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(List.of(
                    task.script() == null ? "" : task.script(), task.inputsOrEmpty(), task.outputsOrEmpty()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash task definition: " + e.getMessage(), e);
        }
    }
}
