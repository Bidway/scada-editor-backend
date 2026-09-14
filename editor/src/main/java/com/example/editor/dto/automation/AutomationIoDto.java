package com.example.editor.dto.automation;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Вход или выход задачи: скрипт обращается к нему по {@code alias}, {@code tag} — путь тега ПЛК. */
public record AutomationIoDto(
        String alias,
        String tag,
        @JsonProperty("value_type") String valueType) {
}
