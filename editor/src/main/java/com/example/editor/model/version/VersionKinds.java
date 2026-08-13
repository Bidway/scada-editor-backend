package com.example.editor.model.version;

import lombok.experimental.UtilityClass;

/**
 * Разбор заголовка {@code X-Save-Kind}.
 * <p>
 * Признак автосохранения приходит заголовком, а не полем тела: тело у сохранения компонентов —
 * голый список, и добавление поля меняло бы форму запроса, которую до ответа фронта держим
 * неизменной. Не прислали — считаем ручным сохранением: ошибиться в эту сторону безопаснее,
 * лишнее автосохранение в истории не мешает, а вот пропавшее ручное — мешает.
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
            throw new IllegalArgumentException("Unknown X-Save-Kind: " + saveKind);
        }
    }
}
