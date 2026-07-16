package com.example.runtime.session;

/**
 * Компонентный Script, выполняемый по действию с фронта (например, нажатие кнопки),
 * а не по изменению тега.
 */
public record ScriptEntry(Long id, Long componentId, String name, String source) {
}
