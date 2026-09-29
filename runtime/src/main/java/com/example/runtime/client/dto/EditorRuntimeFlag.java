package com.example.runtime.client.dto;

/** Ответ {@code GET /api/editor/projects/{id}/runtime}: флаг и номер prod-выпуска (null — выпуска нет). */
public record EditorRuntimeFlag(boolean inOperation, Integer prodVersionNo) {
}
