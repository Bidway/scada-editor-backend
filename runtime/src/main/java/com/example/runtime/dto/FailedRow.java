package com.example.runtime.dto;

/**
 * Строка набора значений, которая не применилась, вместе с причиной.
 * <p>
 * Существует отдельно от {@code failedRows} (там только имена) намеренно: старое поле
 * остаётся нетронутым ради уже работающего экрана, а причина приезжает дополнительно.
 * Различать причины важно — «канала нет» это ошибка конфигурации проекта, которую
 * чинит инженер, а «нет связи с контроллером» это эксплуатационная ситуация, которая
 * может пройти сама. До появления статусов оператор видел и то и другое одинаково.
 *
 * @param rowName имя строки набора
 * @param status  код исхода — см. {@code CommandOutcome}: значения шлюза
 *                (REJECTED_UNKNOWN_TAG, FAILED_NO_CONNECTION, …) либо локальные
 *                (NOT_DELIVERED, NO_CONFIRMATION, NO_TAG, INVALID_VALUE, NO_SESSION)
 * @param message человекочитаемая расшифровка
 */
public record FailedRow(String rowName, String status, String message) {

    /** Значение строки не приводится к её объявленному типу — в ПЛК не отправлялось ничего. */
    public static final String INVALID_VALUE = "INVALID_VALUE";

    /** Локальная строка требует сессии мониторинга, а запрос пришёл без неё. */
    public static final String NO_SESSION = "NO_SESSION";
}
