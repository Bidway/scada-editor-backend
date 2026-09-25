package com.example.editor.service.version;

/** В истории сцены появилась новая версия — любым путём: сохранение, правка свойства, автопривязка, восстановление. */
public record SceneVersionRecorded(Long sceneId, Integer versionNo, String userName) {
}
