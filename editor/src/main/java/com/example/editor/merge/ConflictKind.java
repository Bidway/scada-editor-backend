package com.example.editor.merge;

/** Виды конфликтов из контракта с фронтом, §2. */
public enum ConflictKind {
    /** Обе стороны изменили одну сущность по-разному. */
    BOTH_MODIFIED,
    /** Я правил, они удалили. */
    DELETED_BY_THEM,
    /** Они правили, я удалил. */
    DELETED_BY_YOU,
    /** Обе стороны завели сущность с одним ключом и разным содержимым. */
    BOTH_ADDED
}
