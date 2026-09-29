package com.example.runtime.project;

/** Проект переключён на другой выпуск; мониторам — TREE_CHANGED и свежий SNAPSHOT. */
public record ProjectModelReplaced(Long projectId, int versionNo) {
}
