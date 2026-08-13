package com.example.editor.model.version;

import lombok.experimental.UtilityClass;

/**
 * Разбор поля {@code save_kind} из тела сохранения.
 * <p>
 * Не прислали — считаем ручным сохранением: ошибиться в эту сторону безопаснее, лишнее
 * автосохранение в истории не мешает, а вот пропавшее ручное — мешает.
 */
@UtilityClass
public class VersionKinds {

    public VersionKind orManual(String saveKind) {
        if (saveKind == null || saveKind.isBlank()) {
            return VersionKind.MANUAL;
        }
        try {
            return VersionKind.valueOf(saveKind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown save_kind: " + saveKind);
        }
    }
}
