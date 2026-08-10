package com.example.shared.command;

/**
 * Обработчик отмены команды. Тип записи журнала — параметр: {@code CommandLog} у каждого
 * сервиса свой, он привязан к схеме БД модуля и в shared не переезжает. Без параметра
 * пришлось бы объявить log как Object и кастовать его в каждой реализации.
 */
public interface UndoHandler<L> {

    boolean supports(String commandType);

    CommandResult<?> undo(L log, String userName);
}
