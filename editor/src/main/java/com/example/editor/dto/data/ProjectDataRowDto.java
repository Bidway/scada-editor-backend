package com.example.editor.dto.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** Строка таблицы: ключ и значения по имени колонки, как их прислал клиент. */
public record ProjectDataRowDto(String key, Map<String, JsonNode> values) {
}
