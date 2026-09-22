package com.example.channel.importer;

import java.util.List;
import java.util.Map;

/** Разобранный .cdbx: поля драйвера (для узла проекта) и каналы в порядке файла. */
public record CdbxFile(Map<String, String> driver, List<CdbxChannel> channels) {
}
