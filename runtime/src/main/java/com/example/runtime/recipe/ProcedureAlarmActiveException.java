package com.example.runtime.recipe;

import com.example.runtime.dto.ProcedureStatusDto;

/**
 * Оператор нажал «Продолжить», а авария, из-за которой процедура встала, ещё активна.
 * Продолжить значило бы тут же встать снова и ещё раз дёрнуть насосы.
 */
public class ProcedureAlarmActiveException extends RuntimeException {

    private final transient ProcedureStatusDto status;

    public ProcedureAlarmActiveException(String alarm, ProcedureStatusDto status) {
        super("Авария ещё активна: " + alarm);
        this.status = status;
    }

    public ProcedureStatusDto status() {
        return status;
    }
}
