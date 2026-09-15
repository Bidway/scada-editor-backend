package com.example.editor.exception;

import java.util.List;

public class ProjectDataValidationException extends RuntimeException {

    private final List<ProjectDataValidationError> errors;

    public ProjectDataValidationException(List<ProjectDataValidationError> errors) {
        super("Project data is invalid: " + errors.size() + " error(s)");
        this.errors = List.copyOf(errors);
    }

    public List<ProjectDataValidationError> getErrors() {
        return errors;
    }
}
