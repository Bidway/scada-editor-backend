package com.example.editor.merge;

import java.util.function.Function;

/**
 * Как адресуется и как называется один вид вложенной строки. Нужен, чтобы алгоритм слияния был
 * написан один раз, а не шесть — по числу коллекций компонента.
 *
 * @param entity имя вида для отчётов, как в контракте: {@code script}, {@code component_state}…
 * @param idOf   id строки; {@code null} означает «строка новая»
 * @param keyOf  ключ сопоставления при отсутствии id: имя, а у событий — тип
 */
public record RowSpec<T>(String entity, Function<T, Long> idOf, Function<T, String> keyOf) {
}
