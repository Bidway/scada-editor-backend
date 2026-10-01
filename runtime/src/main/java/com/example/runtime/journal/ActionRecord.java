package com.example.runtime.journal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Строка журнала действий оператора.
 *
 * @param target имя скрипта (ACTION), «операция рецепт» (PROCEDURE); null у TAG_WRITE
 * @param tags   что пытались записать: [{tag, value}] у TAG_WRITE; у ACTION null — теги видны в архиве
 */
public record ActionRecord(Instant ts, String username, Long projectId, String kind,
                           Long componentId, String component, String target,
                           List<Map<String, Object>> tags, String outcome, String error) {

    public static final String KIND_ACTION = "ACTION";
    public static final String KIND_PROCEDURE = "PROCEDURE";
    public static final String KIND_TAG_WRITE = "TAG_WRITE";
    /** Оператор задал локальное свойство в инспекторе объектов; в {@code tags} — [{property, value}]. */
    public static final String KIND_PROPERTY_WRITE = "PROPERTY_WRITE";
    public static final String OK = "OK";
    public static final String ERROR = "ERROR";

    public ActionRecord ok() {
        return new ActionRecord(ts, username, projectId, kind, componentId, component, target, tags, OK, null);
    }

    public ActionRecord failed(String error) {
        return new ActionRecord(ts, username, projectId, kind, componentId, component, target, tags, ERROR, error);
    }
}
