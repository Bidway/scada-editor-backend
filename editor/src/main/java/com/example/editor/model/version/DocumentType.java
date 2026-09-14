package com.example.editor.model.version;

/**
 * Вид документа, у которого есть версии. От него зависит форма {@code content}: у сцены это
 * дерево {@code ComponentResponseDto}, у шаблона — {@code TemplateResponseDto}, у автоматизации —
 * {@code AutomationSetDto} проекта (задачи, переменные, watchdog; {@code target_id} — id проекта).
 * Деревья разные, поэтому и производитель содержимого свой на каждый вид (см. {@code DocumentSource}).
 */
public enum DocumentType {
    SCENE,
    TEMPLATE,
    AUTOMATION
}
