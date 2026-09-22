package com.example.channel.importer;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Старое имя канала .cdbx (LINE1V0.ST) → объектный путь (LINE1.V0.ST). Имя в ПЛК остаётся
 * прежним и хранится параметром узла: контроллер знает тег только под ним.
 */
public final class ObjectPathMapper {

    public record LegacyName(String object, String field) {
    }

    /** LINE1V0, CAB1HLA1: контейнер и прибор, прибор начинается с буквы. */
    private static final Pattern CONTAINER = Pattern.compile("^(LINE\\d+|CAB\\d+)([A-Za-z].*)$");
    /** Техобъект линии: параметры, рецепт, статистика, команды. */
    private static final Pattern TECH_OBJECT = Pattern.compile("^OBJECT(\\d+)$");
    private static final Pattern LETTERS = Pattern.compile("^[A-Za-z_]+");

    private ObjectPathMapper() {
    }

    public static Optional<LegacyName> split(String name) {
        int dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new LegacyName(name.substring(0, dot), name.substring(dot + 1)));
    }

    public static List<String> objectSegments(String object) {
        Matcher container = CONTAINER.matcher(object);
        if (container.matches()) {
            return List.of(container.group(1), container.group(2));
        }
        Matcher tech = TECH_OBJECT.matcher(object);
        if (tech.matches()) {
            return List.of("LINE" + tech.group(1), "OBJECT");
        }
        return List.of("STATION", object);
    }

    /** deviceType для реестра шлюза: буквенная часть прибора (V0 → V, WATCHDOG1 → WATCHDOG). */
    public static String deviceType(String object) {
        List<String> segments = objectSegments(object);
        Matcher letters = LETTERS.matcher(segments.get(segments.size() - 1));
        return letters.find() ? letters.group() : object;
    }
}
