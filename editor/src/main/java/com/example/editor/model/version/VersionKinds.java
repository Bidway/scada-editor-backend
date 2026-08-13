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
        VersionKind kind;
        try {
            kind = VersionKind.valueOf(saveKind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown save_kind: " + saveKind);
        }
        // RESTORE клиент прислать не может: этот kind ставит только сам сервер при
        // восстановлении версии, и от него зависит обход проверки based_on_version — иначе
        // сохранение с save_kind=RESTORE обходило бы её.
        if (kind == VersionKind.RESTORE) {
            throw new IllegalArgumentException("Unknown save_kind: " + saveKind);
        }
        return kind;
    }
}
