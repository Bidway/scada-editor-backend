package com.example.editor.exception;

/** Ввод в эксплуатацию без prod-выпуска: runtime нечего крутить. */
public class NoProdReleaseException extends RuntimeException {
    public NoProdReleaseException(Long projectId) {
        super("У проекта " + projectId + " нет prod-выпуска — сначала выпустите проект и назначьте выпуск prod");
    }
}
