package com.example.editor.merge;

/**
 * Одна строка списка {@code conflicts}.
 *
 * @param entity вид сущности: component, component_property, script, component_state,
 *               component_event, binding
 * @param path   человекочитаемый путь («Насос-1 / Уставка») — он идёт человеку на экран,
 *               а не в код клиента
 * @param base   значение в базовой версии; {@code null}, если сущности там не было
 * @param yours  моё значение; {@code null}, если я её удалил
 * @param theirs чужое значение; {@code null}, если её удалили они
 */
public record MergeConflict(ConflictKind kind, String entity, String path,
                            String base, String yours, String theirs) {
}
