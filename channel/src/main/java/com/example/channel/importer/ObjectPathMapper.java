package com.example.channel.importer;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Старое имя канала .cdbx (LINE1V0.ST) → объектный путь (LINE1.V0.ST). Имя в ПЛК остаётся
 * прежним и хранится параметром узла: контроллер знает тег только под ним.
 *
 * <p>Сначала спрашивается справочник проекта {@link PlcProject} из вложений импорта — он делит
 * имя точно. Чего в нём нет (или вложений не дали) — раскладывается эвристикой: контейнер это
 * всё, что стоит перед известным типом прибора с номером в конце имени.
 */
public final class ObjectPathMapper {

    public record LegacyName(String object, String field) {
    }

    /**
     * Типы приборов ptusa, самый длинный раньше короткого: у CIPV101 надо узнать V, а у
     * LINE1WATCHDOG1 — WATCHDOG, а не HA внутри него.
     */
    private static final String DEVICE_TYPES =
            "WATCHDOG|FQT|HLA|LT|LS|GS|TE|QT|PT|FS|DI|DO|AI|AO|SB|VC|HA|HL|V|M|G|N";
    private static final Pattern DEVICE = Pattern.compile("(" + DEVICE_TYPES + ")\\d+$");
    /** Техобъект: параметры, рецепт, статистика, команды. Номер — порядковый в main.objects.lua. */
    private static final Pattern TECH_OBJECT = Pattern.compile("^OBJECT(\\d+)$");
    private static final Pattern LETTERS = Pattern.compile("^[A-Za-z_]+");
    private static final String STATION = "STATION";

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
        return objectSegments(object, PlcProject.empty());
    }

    /** Нашёлся ли объект в справочнике: иначе он разложен эвристикой и идёт в отчёт импорта. */
    public static boolean known(String object, PlcProject plc) {
        if (plc.device(object).isPresent()) {
            return true;
        }
        Matcher tech = TECH_OBJECT.matcher(object);
        return tech.matches() && plc.object(Integer.parseInt(tech.group(1))).isPresent();
    }

    public static List<String> objectSegments(String object, PlcProject plc) {
        Optional<PlcProject.Device> device = plc.device(object);
        if (device.isPresent()) {
            String container = device.get().container();
            return List.of(container.isEmpty() ? STATION : container, device.get().device());
        }
        Matcher tech = TECH_OBJECT.matcher(object);
        if (tech.matches()) {
            return techObjectSegments(Integer.parseInt(tech.group(1)), plc);
        }
        return heuristicSegments(object);
    }

    /**
     * Узел техобъекта именуется техническим типом (LINE1.MAIN_CIP_MODULE, TANK1.MIX_NODE), а не
     * словом OBJECT: у танка и его узла перемешивания совпадают и name_eplan, и номер, так что по
     * ним одним объекты слились бы в один узел вместе с полями. Без справочника — прежнее
     * LINE&lt;n&gt;.OBJECT.
     */
    private static List<String> techObjectSegments(int number, PlcProject plc) {
        return plc.object(number)
                .map(object -> List.of(object.container(), object.node()))
                .orElseGet(() -> List.of("LINE" + number, "OBJECT"));
    }

    /**
     * Запасная разбивка по имени: TANK1V1 → TANK1.V1, CIPV101 → CIP.V101, MCA4LINE1DI1 →
     * MCA4LINE1.DI1. Берётся самое левое вхождение типа с номером — иначе у M15AI11 контейнером
     * стало бы M15AI1.
     */
    private static List<String> heuristicSegments(String object) {
        Matcher device = DEVICE.matcher(object);
        if (device.find() && device.start() > 0) {
            return List.of(object.substring(0, device.start()), object.substring(device.start()));
        }
        return List.of(STATION, object);
    }

    /** deviceType для реестра шлюза: буквенная часть прибора (V0 → V, WATCHDOG1 → WATCHDOG). */
    public static String deviceType(String object) {
        return deviceType(object, PlcProject.empty());
    }

    public static String deviceType(String object, PlcProject plc) {
        List<String> segments = objectSegments(object, plc);
        Matcher letters = LETTERS.matcher(segments.get(segments.size() - 1));
        return letters.find() ? letters.group() : object;
    }
}
