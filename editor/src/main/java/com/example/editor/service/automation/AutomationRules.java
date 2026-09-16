package com.example.editor.service.automation;

import com.example.editor.exception.AutomationValidationError;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Правила, общие для набора задач проекта и для шаблона задачи: диапазоны периодов, псевдонимы
 * входов-выходов, допустимые типы значений.
 * <p>
 * Вынесено из {@link AutomationSetValidator} ради {@link AutomationTaskTemplateValidator}: шаблон
 * — та же задача без тегов и проекта, и разойтись эти проверки не должны.
 */
final class AutomationRules {

    static final Set<String> VALUE_TYPES = Set.of("bool", "int", "float", "string");

    static final int PERIOD_MIN_MS = 100;
    static final int PERIOD_MAX_MS = 3_600_000;
    static final int STALE_MIN_MS = 100;
    static final int STALE_MAX_MS = 86_400_000;

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private AutomationRules() {
    }

    /** Период, устаревание и таймаут: таймаут не больше половины периода, иначе сорванный прогон догоняет следующий. */
    static void checkTiming(List<AutomationValidationError> errors, String task,
                            Integer periodMs, Integer staleAfterMs, Integer timeoutMs) {
        checkRange(errors, task, "period_ms", periodMs, PERIOD_MIN_MS, PERIOD_MAX_MS);
        checkRange(errors, task, "stale_after_ms", staleAfterMs, STALE_MIN_MS, STALE_MAX_MS);
        if (periodMs != null) {
            checkRange(errors, task, "timeout_ms", timeoutMs, 1, Math.max(1, periodMs / 2));
        }
    }

    static void checkRange(List<AutomationValidationError> errors, String task, String field,
                           Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            errors.add(error(task, field, field + " должен быть от " + min + " до " + max + ", получено " + value));
        }
    }

    /**
     * Общая часть входа или выхода: псевдоним допустим и не повторяется в своём списке, тип
     * значения из списка. Тег проверяет только валидатор набора — у шаблона его нет.
     *
     * @param aliases уже встреченные псевдонимы этого списка; метод пополняет набор
     */
    static void checkIo(List<AutomationValidationError> errors, String task, String field,
                        String alias, String valueType, Set<String> aliases) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            errors.add(error(task, field, "Алиас '" + alias + "' недопустим"));
        } else if (!aliases.add(alias)) {
            errors.add(error(task, field, "Алиас '" + alias + "' повторяется"));
        }
        if (!isValueType(valueType)) {
            errors.add(error(task, field, "'" + alias + "': value_type должен быть одним из " + VALUE_TYPES));
        }
    }

    /** {@code Set.of(...).contains(null)} бросает NPE — отсюда явная проверка. */
    static boolean isValueType(String valueType) {
        return valueType != null && VALUE_TYPES.contains(valueType);
    }

    static AutomationValidationError error(String task, String field, String message) {
        return new AutomationValidationError(task, field, message);
    }
}
