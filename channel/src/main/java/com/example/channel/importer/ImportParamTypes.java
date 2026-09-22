package com.example.channel.importer;

import com.example.channel.model.Description;
import com.example.channel.repository.DescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Типы параметров, которые пишет импорт .cdbx. На живом стенде первые 14 уже есть в
 * channel.description (по ним база когда-то заводилась), на новой или тестовой базе справочник
 * пуст. Поэтому все заводятся при старте, если их нет, а id ищутся по имени: на разных стендах
 * id могут разойтись.
 */
@Component
@RequiredArgsConstructor
public class ImportParamTypes implements ApplicationRunner {

    public static final String ENABLED = "Включен";
    public static final String ACCESS = "Тип доступа";
    public static final String DESCRIPTION = "Описание";
    public static final String MAX_CHANNELS = "Максимальное количество каналов";
    public static final String PORT_NAME = "Имя порта";
    public static final String SPEED = "Скорость";
    public static final String PARITY = "Четность";
    public static final String DATA_BITS = "Биты данных";
    public static final String STOP_BITS = "Стоп биты";
    public static final String REQUEST_TYPE = "Тип опроса";
    public static final String PERIOD = "Период";
    public static final String DELTA = "Дельта";
    public static final String AVERAGING = "Интервал усреднения";
    public static final String PROTOCOL = "Протокол";
    /** Имя канала в старом формате (LINE1V0.ST) — под ним тег знает контроллер. */
    public static final String PLC_NAME = "Имя в ПЛК";
    /** INT32 / FLOAT / STRING для реестра шлюза. */
    public static final String DATA_TYPE = "Тип данных";
    /** На узле проекта: имя файла. Метка «создан импортом», без неё удаление запрещено. */
    public static final String SOURCE = "Источник импорта";

    private static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        for (String name : new String[]{ACCESS, MAX_CHANNELS, PORT_NAME, SPEED, PARITY, DATA_BITS, STOP_BITS,
                REQUEST_TYPE, PERIOD, DELTA, AVERAGING, PROTOCOL, PLC_NAME, DATA_TYPE, SOURCE}) {
            KINDS.put(name, "input");
        }
        KINDS.put(ENABLED, "checkbox");
        KINDS.put(DESCRIPTION, "textarea");
    }

    private final DescriptionRepository descriptions;

    @Override
    public void run(ApplicationArguments args) {
        ids();
    }

    /** id каждого типа по имени; недостающие заводятся. */
    @Transactional
    public synchronized Map<String, Long> ids() {
        Map<String, Long> ids = new LinkedHashMap<>();
        KINDS.forEach((name, kind) -> {
            Description description = descriptions.findByName(name);
            if (description == null) {
                description = new Description();
                description.setName(name);
                description.setType(kind);
                description = descriptions.save(description);
            }
            ids.put(name, description.getId());
        });
        return ids;
    }
}
