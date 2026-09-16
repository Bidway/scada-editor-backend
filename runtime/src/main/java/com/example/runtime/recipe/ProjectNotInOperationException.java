package com.example.runtime.recipe;

/**
 * Проект не введён в эксплуатацию: runtime его не поднял, тегов и состояния свойств у него нет.
 * Отдельное состояние, а не пустой экран: «проект выключен» должно быть видно, а не выглядеть
 * неисправностью монитора.
 */
public class ProjectNotInOperationException extends RuntimeException {

    public ProjectNotInOperationException(Long projectId) {
        super("Проект " + projectId + " не введён в эксплуатацию — включите его в редакторе");
    }
}
