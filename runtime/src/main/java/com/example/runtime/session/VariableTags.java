package com.example.runtime.session;

/**
 * Адрес переменной проекта сервиса automation. Для фронта это обычный {@code tag_id}
 * {@code @var.<имя>}; внутри runtime переменная живёт под ключом сообщения {@code automation.state}
 * {@code var:<projectId>:<имя>} — у разных проектов могут быть одноимённые переменные, а
 * {@code TagValueRouter} хранит состояние по ключу, общему для всех сессий.
 */
public final class VariableTags {

    public static final String PREFIX = "@var.";
    public static final String KEY_PREFIX = "var:";

    private VariableTags() {
    }

    public static boolean isVariable(String tagId) {
        return tagId != null && tagId.startsWith(PREFIX);
    }

    public static boolean isVariableKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    /** {@code @var.line1.mode} в проекте 8501 → {@code var:8501:line1.mode}. */
    public static String subscriptionKey(Long projectId, String tagId) {
        return KEY_PREFIX + projectId + ":" + tagId.substring(PREFIX.length());
    }

    /** {@code var:8501:line1.mode} → {@code @var.line1.mode}; путь тега ПЛК — как есть. */
    public static String displayId(String key) {
        if (!isVariableKey(key)) {
            return key;
        }
        int colon = key.indexOf(':', KEY_PREFIX.length());
        return colon < 0 ? key : PREFIX + key.substring(colon + 1);
    }
}
