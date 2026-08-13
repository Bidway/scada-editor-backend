package com.example.editor.exception;

import lombok.Getter;

/**
 * Клиент основывался на версии, которая уже не последняя: документ успел сохранить кто-то ещё.
 * <p>
 * Пока это безусловный отказ. План 3b заменит его на «сначала попытаться слить»: код останется
 * {@code 409}, {@code base_version} и {@code current_version} останутся, добавится список
 * {@code conflicts}, а {@code error} станет {@code merge_conflict}. Форма выбрана так, чтобы
 * фронту хватило одной обработки на оба случая.
 */
@Getter
public class VersionMismatchException extends RuntimeException {

    private final Integer baseVersion;
    private final Integer currentVersion;

    public VersionMismatchException(Integer baseVersion, Integer currentVersion) {
        super("Save is based on version " + baseVersion + ", but current is " + currentVersion);
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
    }
}
