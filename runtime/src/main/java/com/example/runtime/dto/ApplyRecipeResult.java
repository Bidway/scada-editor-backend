package com.example.runtime.dto;

import java.util.List;

/**
 * Отчёт о применении набора значений.
 *
 * @param total         сколько значений было в резолве
 * @param sent          команд отправлено в теги → ПЛК
 * @param localApplied  локальных строк записано в состояние сессии
 * @param failed        значений не применено (сбой отправки или недоступная сессия)
 * @param failedRows    имена строк, которые не применились
 * @param unmatchedRows строки набора, которых больше нет в таблице (применять не к чему)
 */
public record ApplyRecipeResult(Long recipeId,
                                int total,
                                int sent,
                                int localApplied,
                                int failed,
                                List<String> failedRows,
                                List<String> unmatchedRows) {
}
