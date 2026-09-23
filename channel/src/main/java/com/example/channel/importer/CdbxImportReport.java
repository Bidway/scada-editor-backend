package com.example.channel.importer;

import java.util.List;

/**
 * Итог импорта. merged — имена, чей путь уже занят другим каналом (одна переменная ПЛК в двух
 * группах); guessedType — поля вне таблицы типов; skipped — имена без поля; unmapped — объекты,
 * которых не нашлось в приложенных исходниках проекта и которые разложены эвристикой.
 */
public record CdbxImportReport(String root, int nodes, int channels, List<String> merged,
                               List<String> guessedType, List<String> skipped, List<String> unmapped) {
}
