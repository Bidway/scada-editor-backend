package com.example.runtime.session;

/**
 * Привязка "тег изменился -> выполнить onChange свойства componentPropertyId".
 */
public record OnChangeBinding(Long componentId, Long componentPropertyId, String scriptSource) {
}
