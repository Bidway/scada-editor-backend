package com.example.editor.model.version;

/**
 * Вид документа, у которого есть версии. От него зависит форма {@code content}: у сцены это
 * дерево {@code ComponentResponseDto}, у шаблона — {@code TemplateResponseDto}. Деревья разные,
 * поэтому и производитель содержимого свой на каждый вид (см. {@code DocumentSource}).
 */
public enum DocumentType {
    SCENE,
    TEMPLATE
}
