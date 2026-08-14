package com.example.editor.merge;

/** Одна строка отчёта {@code merged.changes}: что подмешалось с чужой стороны. */
public record MergeChange(String entity, String path, ChangeKind change) {
}
