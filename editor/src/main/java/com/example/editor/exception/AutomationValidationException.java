package com.example.editor.exception;

import lombok.Getter;

import java.util.List;

/** Набор автоматизации не прошёл проверку — все нарушения сразу, а не первое. */
@Getter
public class AutomationValidationException extends RuntimeException {

    private final List<AutomationValidationError> errors;

    public AutomationValidationException(List<AutomationValidationError> errors) {
        super("Automation set is invalid: " + errors.size() + " error(s)");
        this.errors = List.copyOf(errors);
    }
}
