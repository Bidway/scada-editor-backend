package com.example.channel.importer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Справочник проекта ptusa из вложений импорта: приборы из {@code main.io.lua} и технологические
 * объекты из {@code main.objects.lua}. В самом .cdbx имена приборов слиты в одну строку
 * (CIPV101, MCA4LINE1DI1), а объекты безлики (OBJECT1…OBJECT17) — эти два файла дают точное
 * деление и описания. Без вложений справочник пуст, и разбивка идёт по эвристике
 * {@link ObjectPathMapper}.
 */
public record PlcProject(Map<String, Device> devices, List<TechObject> objects) {

    /** Прибор: CIPV101 → контейнер CIP, прибор V101, описание «CIP+ Магистральный (NO)». */
    public record Device(String container, String device, String description) {
    }

    /** Техобъект: OBJECT6 → контейнер TANK1, узел MIX_NODE (по base_tech_object). */
    public record TechObject(String container, String node) {
    }

    public static PlcProject empty() {
        return new PlcProject(Map.of(), List.of());
    }

    public boolean isEmpty() {
        return devices.isEmpty() && objects.isEmpty();
    }

    public Optional<Device> device(String name) {
        return Optional.ofNullable(devices.get(name));
    }

    /** Номер из имени OBJECT&lt;n&gt; — это порядковый номер объекта в main.objects.lua. */
    public Optional<TechObject> object(int number) {
        return number >= 1 && number <= objects.size() ? Optional.of(objects.get(number - 1)) : Optional.empty();
    }
}
