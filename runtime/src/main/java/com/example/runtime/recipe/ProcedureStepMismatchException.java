package com.example.runtime.recipe;

import com.example.runtime.dto.ProcedureStatusDto;

/**
 * Оператор подтвердил шаг, который к моменту запроса уже закрыт. Возникает, когда на проект
 * смотрят двое и второй нажал «Подтвердить» на устаревшем экране: молча закрыть текущий шаг
 * значило бы подтвердить не то, что человек видел.
 */
public class ProcedureStepMismatchException extends RuntimeException {

    private final transient ProcedureStatusDto status;

    public ProcedureStepMismatchException(ProcedureStatusDto status) {
        super("Шаг уже сменился — подтверждать нужно текущий");
        this.status = status;
    }

    public ProcedureStatusDto status() {
        return status;
    }
}
