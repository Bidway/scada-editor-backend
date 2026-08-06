package com.example.runtime.kafka;

/**
 * Исход команды записи тега — то, что произошло <b>в ПЛК</b>, а не факт отправки.
 * <p>
 * Раньше успехом считалось подтверждение брокера: «Kafka приняла сообщение». Команда,
 * которую шлюз затем отбросил — канала нет, узел только на чтение, контроллер отвалился, —
 * для оператора выглядела в точности как применённая. Теперь исход приходит из топика
 * {@code scada-command-results}, а {@link #status} различает причины без разбора текста.
 * <p>
 * {@link #NO_CONFIRMATION} — единственный статус, означающий <b>незнание</b>: команда ушла
 * в брокер, но ответ шлюза не пришёл вовремя. Она могла и примениться. Показывать её
 * оператору как отказ нельзя — только как «результат неизвестен».
 *
 * @param applied подтверждено ли применение контроллером
 * @param status  код исхода: значения {@code CommandStatus} шлюза (APPLIED,
 *                REJECTED_UNKNOWN_TAG, REJECTED_NOT_WRITABLE, REJECTED_TYPE_MISMATCH,
 *                REJECTED_PROTOCOL_UNSUPPORTED, FAILED_NO_CONNECTION, FAILED_WRITE)
 *                либо один из локальных, объявленных здесь
 * @param message человекочитаемая расшифровка — для лога и подсказки оператору
 */
public record CommandOutcome(boolean applied, String status, String message) {

    /** Записано и подтверждено контроллером. Значение совпадает с одноимённым у шлюза. */
    public static final String APPLIED = "APPLIED";

    /** Брокер не принял сообщение — команда не покинула процесс. Локальный статус. */
    public static final String NOT_DELIVERED = "NOT_DELIVERED";

    /** Шлюз не ответил за отведённое время. Применена команда или нет — <b>неизвестно</b>. */
    public static final String NO_CONFIRMATION = "NO_CONFIRMATION";

    /** Отправлять нечего: свойство не привязано к тегу. Локальный статус. */
    public static final String NO_TAG = "NO_TAG";

    public static CommandOutcome applied(String message) {
        return new CommandOutcome(true, APPLIED, message);
    }

    public static CommandOutcome failure(String status, String message) {
        return new CommandOutcome(false, status, message);
    }

    /** Исход, как его прислал шлюз: {@code success} и код статуса берутся с провода. */
    public static CommandOutcome fromGateway(boolean success, String status, String message) {
        return new CommandOutcome(success, status != null ? status : APPLIED, message);
    }

    /**
     * Известен ли исход достоверно. {@code false} только для {@link #NO_CONFIRMATION}:
     * во всех остальных случаях мы знаем, применилась команда или нет.
     */
    public boolean isKnown() {
        return !NO_CONFIRMATION.equals(status);
    }
}
