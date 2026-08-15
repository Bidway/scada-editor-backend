package com.example.editor.merge;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Как адресуется и как называется один вид вложенной строки. Нужен, чтобы алгоритм слияния был
 * написан один раз, а не шесть — по числу коллекций компонента.
 *
 * @param entity имя вида для отчётов, как в контракте: {@code script}, {@code component_state}…
 * @param idOf   id строки; {@code null} означает «строка новая»
 * @param keyOf  ключ сопоставления при отсутствии id: имя, а у событий — тип
 * @param setId  простановка id обратно в объект. Нужна {@code SceneMerger}, когда строка без id
 *               на моей стороне сопоставлена с базовой/чужой по имени (см. {@code nameAlias}):
 *               идентичность решена на этапе сопоставления, и её нужно перенести на сам объект —
 *               иначе порядок и запись на диск увидят его снова как {@code id == null}.
 */
public record RowSpec<T>(String entity, Function<T, Long> idOf, Function<T, String> keyOf,
                         BiConsumer<T, Long> setId) {
}
