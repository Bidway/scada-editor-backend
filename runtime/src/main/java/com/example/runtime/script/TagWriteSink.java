package com.example.runtime.script;

/**
 * Приёмник вызовов {@code writeTag(propertyName, value)} из скрипта.
 * <p>
 * Скрипт не публикует команду сам: он лишь заявляет намерение записать тег, а куда
 * и как это уедет — решает вызывающий ({@code TagValueRouter} / {@code RuntimeSessionService}).
 * Благодаря этому {@code ScriptEngineService} остаётся движком без знания о Kafka,
 * а в самом скрипте не появляется ни сети, ни host-объектов.
 *
 * @see ScriptEngineService#runAction
 */
@FunctionalInterface
public interface TagWriteSink {

    /** Ничего не делает — для скриптов, которым запись тегов не разрешена/не нужна. */
    TagWriteSink NOOP = (propertyName, value) -> {
    };

    /**
     * @param propertyName имя свойства компонента, к которому привязан тег
     * @param value        значение как его передал скрипт (boolean/number/string)
     */
    void write(String propertyName, Object value);
}
