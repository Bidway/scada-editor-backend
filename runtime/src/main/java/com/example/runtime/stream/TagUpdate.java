package com.example.runtime.stream;

/**
 * Значение тега в WS-кадре {@code {"type":"UPDATE","tags":[…]}}.
 * <p>
 * {@code value} и {@code ts} всегда описывают <b>одно и то же измерение</b>, а
 * {@code quality} говорит, актуально ли оно сейчас. При потере связи значение и его
 * метка времени остаются от последнего достоверного чтения, а качество становится
 * {@link #BAD} — так фронт показывает не пустоту, а «было открыто, связь потеряна
 * 40 секунд назад»: возраст значения считается как {@code now - ts}.
 *
 * @param tagId   путь узла базы каналов — он же {@code ComponentProperty.tagId},
 *                он же Kafka-key телеметрии
 * @param value   последнее достоверное значение, всегда строкой ({@code "true"},
 *                {@code "72.7"}); {@code null} — достоверного значения ещё не было
 * @param ts      момент снятия значения с контроллера (epoch ms); {@code 0}, если
 *                достоверного значения ещё не было
 * @param quality {@link #GOOD} — {@code value} актуально; любое другое значение
 *                недостоверно, и фронт рисует «нет данных»
 */
public record TagUpdate(String tagId, String value, long ts, String quality) {

    /** Значение прочитано с контроллера и актуально. */
    public static final String GOOD = "GOOD";

    /** Недостоверно: нет связи, плохой статус узла либо значение ещё ни разу не приходило. */
    public static final String BAD = "BAD";
}
