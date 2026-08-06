package com.example.runtime.dto;

import java.util.List;

/**
 * Отчёт о применении набора значений.
 * <p>
 * {@code sent} означает <b>применено контроллером</b>, а не «отправлено в брокер»:
 * исход каждой команды приходит от шлюза в {@code scada-command-results}. Раньше сюда
 * попадали и команды, которые шлюз затем отбросил, — оператор видел успех там, где в
 * ПЛК ничего не изменилось.
 *
 * @param total         сколько значений было в резолве
 * @param sent          команд применено в ПЛК (подтверждено шлюзом)
 * @param localApplied  локальных строк записано в состояние сессии
 * @param failed        значений не применено
 * @param failedRows    имена строк, которые не применились
 * @param unmatchedRows строки набора, которых больше нет в таблице (применять не к чему)
 * @param failures      те же строки, что в {@code failedRows}, но с кодом причины и
 *                      расшифровкой; поле добавлено рядом со старым, чтобы не ломать
 *                      уже работающий экран применения набора
 */
public record ApplyRecipeResult(Long recipeId,
                                int total,
                                int sent,
                                int localApplied,
                                int failed,
                                List<String> failedRows,
                                List<String> unmatchedRows,
                                List<FailedRow> failures) {
}
