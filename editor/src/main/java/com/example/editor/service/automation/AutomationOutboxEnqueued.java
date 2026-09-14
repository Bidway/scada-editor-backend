package com.example.editor.service.automation;

/** В outbox появилась строка для проекта. Передатчик слушает его после коммита транзакции. */
public record AutomationOutboxEnqueued(Long projectId) {
}
