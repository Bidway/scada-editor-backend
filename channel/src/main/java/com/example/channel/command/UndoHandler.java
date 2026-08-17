package com.example.channel.command;

/**
 * Обработчик отмены команды. Тип записи журнала — параметр: {@code CommandLog} привязан к схеме
 * БД сервиса и лежит в {@code config.command}, а не рядом с этим интерфейсом. Без параметра
 * пришлось бы объявить log как Object и кастовать его в каждой реализации.
 */
public interface UndoHandler<L> {

    boolean supports(String commandType);

    CommandResult<?> undo(L log, String userName);
}
