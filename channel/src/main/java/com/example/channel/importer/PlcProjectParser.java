package com.example.channel.importer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор вложений импорта — исходников проекта ptusa. Оба файла необязательны и разбираются
 * поверхностно, регулярными выражениями: нужны три поля прибора и четыре поля объекта, а не
 * весь Lua.
 */
public final class PlcProjectParser {

    /**
     * {@code dtype} прибора → буквы его типа в имени. Снято с main.io.lua обоих проектов
     * (мойка BN1_MCA1 и танки нормализации молока), расхождений нет. По этой таблице имя
     * делится однозначно: у CIPV101 тип V, значит контейнер CIP, а не CIPV.
     */
    private static final Map<Integer, String> DEVICE_TYPES = Map.ofEntries(
            Map.entry(0, "V"), Map.entry(1, "VC"), Map.entry(2, "M"), Map.entry(3, "LS"),
            Map.entry(4, "TE"), Map.entry(5, "FS"), Map.entry(6, "GS"), Map.entry(7, "FQT"),
            Map.entry(8, "LT"), Map.entry(9, "QT"), Map.entry(12, "SB"), Map.entry(13, "DI"),
            Map.entry(14, "DO"), Map.entry(15, "AI"), Map.entry(16, "AO"), Map.entry(18, "PT"),
            Map.entry(21, "HLA"), Map.entry(25, "G"), Map.entry(26, "WATCHDOG"));

    private static final Pattern DEVICE = Pattern.compile(
            "name\\s*=\\s*'([^']*)'\\s*,\\s*\\R\\s*descr\\s*=\\s*'([^']*)'\\s*,\\s*\\R\\s*dtype\\s*=\\s*(\\d+)");
    private static final Pattern TECH_OBJECT = Pattern.compile(
            "\\bn\\s*=\\s*(\\d+)\\s*,\\s*\\R\\s*tech_type\\s*=\\s*\\d+[\\s\\S]{0,400}?"
                    + "name_eplan\\s*=\\s*'([^']*)'[\\s\\S]{0,400}?base_tech_object\\s*=\\s*'([^']*)'");

    private PlcProjectParser() {
    }

    public static PlcProject parse(byte[] ioLua, byte[] objectsLua) {
        return new PlcProject(devices(text(ioLua)), objects(text(objectsLua)));
    }

    private static String text(byte[] content) {
        return content == null || content.length == 0 ? "" : new String(content, StandardCharsets.UTF_8);
    }

    /** Приборы: имя → контейнер, прибор, описание. Имя, не сходящееся с типом, пропускается. */
    private static Map<String, PlcProject.Device> devices(String lua) {
        Map<String, PlcProject.Device> devices = new LinkedHashMap<>();
        Matcher matcher = DEVICE.matcher(lua);
        while (matcher.find()) {
            String name = matcher.group(1);
            String type = DEVICE_TYPES.get(Integer.parseInt(matcher.group(3)));
            if (type == null) {
                continue;
            }
            Matcher split = Pattern.compile("^(.*)" + Pattern.quote(type) + "(\\d+)$").matcher(name);
            if (split.matches()) {
                devices.put(name, new PlcProject.Device(split.group(1), type + split.group(2), matcher.group(2)));
            }
        }
        return devices;
    }

    /**
     * Техобъекты в порядке файла: i-й объект — это OBJECT&lt;i&gt; в .cdbx. Контейнер —
     * {@code name_eplan} с номером (TANK1), узел — {@code base_tech_object} в верхнем регистре
     * (TANK, MIX_NODE, LINE_IN): по одному только name_eplan+n объекты не различаются, у танка и
     * его узла перемешивания они совпадают.
     */
    private static List<PlcProject.TechObject> objects(String lua) {
        List<PlcProject.TechObject> objects = new ArrayList<>();
        Matcher matcher = TECH_OBJECT.matcher(lua);
        while (matcher.find()) {
            objects.add(new PlcProject.TechObject(
                    matcher.group(2) + matcher.group(1), matcher.group(3).toUpperCase()));
        }
        return objects;
    }
}
