package com.example.runtime.script;

/**
 * Три приёмника записи, которые скрипт видит одновременно — {@code writeTag} (имя свойства
 * текущего компонента), {@code writeTagPath} (абсолютный путь тега, любого проекта) и
 * {@code writeProjectTag} (короткий путь в рамках текущего проекта, см.
 * {@code TagSubscriptionIndex#resolveTagPath}). Отдельная запись, а не три позиционных
 * параметра {@link TagWriteSink} подряд у {@code execute}/{@code runOnChange}/{@code runAction}:
 * три параметра одного типа рядом — верный способ однажды перепутать порядок при вызове.
 */
public record ScriptWriteSinks(TagWriteSink byProperty, TagWriteSink byPath, TagWriteSink byProjectTag) {

    public static final ScriptWriteSinks NOOP =
            new ScriptWriteSinks(TagWriteSink.NOOP, TagWriteSink.NOOP, TagWriteSink.NOOP);
}
