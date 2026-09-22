package com.example.channel.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectPathMapperTest {

    @Test
    void прибор_линии_и_шкафа_раскладывается_на_контейнер_и_прибор() {
        assertThat(ObjectPathMapper.objectSegments("LINE1V0")).isEqualTo(List.of("LINE1", "V0"));
        assertThat(ObjectPathMapper.objectSegments("CAB1HLA1")).isEqualTo(List.of("CAB1", "HLA1"));
    }

    @Test
    void техобъект_уходит_в_свою_линию_а_прочее_в_STATION() {
        assertThat(ObjectPathMapper.objectSegments("OBJECT2")).isEqualTo(List.of("LINE2", "OBJECT"));
        assertThat(ObjectPathMapper.objectSegments("SYSTEM")).isEqualTo(List.of("STATION", "SYSTEM"));
        assertThat(ObjectPathMapper.objectSegments("LT1")).isEqualTo(List.of("STATION", "LT1"));
    }

    @Test
    void имя_делится_по_первой_точке_без_точки_не_разбирается() {
        assertThat(ObjectPathMapper.split("OBJECT1.RT_PAR_F[1]"))
                .contains(new ObjectPathMapper.LegacyName("OBJECT1", "RT_PAR_F[1]"));
        assertThat(ObjectPathMapper.split("SYSTEM")).isEmpty();
    }

    @Test
    void тип_прибора_буквенная_часть() {
        assertThat(ObjectPathMapper.deviceType("LINE1WATCHDOG1")).isEqualTo("WATCHDOG");
        assertThat(ObjectPathMapper.deviceType("OBJECT1")).isEqualTo("OBJECT");
        assertThat(ObjectPathMapper.deviceType("LT1")).isEqualTo("LT");
    }
}
