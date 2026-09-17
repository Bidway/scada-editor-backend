package com.example.runtime.assignment;

import java.util.List;

/** Назначение нарушает правило «один топик/проект — один экземпляр» или не покрывает теги проекта. */
public class AssignmentConflictException extends RuntimeException {

    private final List<String> uncoveredPaths;

    public AssignmentConflictException(String message) {
        this(message, List.of());
    }

    public AssignmentConflictException(String message, List<String> uncoveredPaths) {
        super(message);
        this.uncoveredPaths = List.copyOf(uncoveredPaths);
    }

    public List<String> uncoveredPaths() {
        return uncoveredPaths;
    }
}
