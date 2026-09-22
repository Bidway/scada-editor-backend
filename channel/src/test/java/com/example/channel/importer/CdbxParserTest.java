package com.example.channel.importer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdbxParserTest {

    private static byte[] mini() throws Exception {
        try (InputStream in = CdbxParserTest.class.getResourceAsStream("/cdbx/mini.cdbx")) {
            return in.readAllBytes();
        }
    }

    /** Буква перед <?xml — как в настоящем BN1 MCA1 NEW.cdbx; обычный парсер на ней падает. */
    @Test
    void мусор_перед_xml_не_мешает_и_драйвер_читается() throws Exception {
        CdbxFile file = CdbxParser.parse(mini());

        assertThat(file.channels()).hasSize(7);
        assertThat(file.driver()).containsEntry("descr", "MINI MCA").containsEntry("PORTNAME", "COM3")
                .containsEntry("access", "2");
    }

    @Test
    void имя_и_описание_делятся_по_двойному_дефису_а_без_него_всё_имя() throws Exception {
        CdbxFile file = CdbxParser.parse(mini());

        CdbxChannel valve = file.channels().get(0);
        assertThat(valve.name()).isEqualTo("LINE1V0.ST");
        assertThat(valve.description()).isEqualTo("Клапан V00");
        assertThat(valve.group()).isEqualTo("V_ST");

        CdbxChannel system = file.channels().get(3);
        assertThat(system.name()).isEqualTo("SYSTEM.UP_TIME");
        assertThat(system.description()).isEmpty();
        assertThat(system.requestPeriod()).isEqualTo("3000");
    }

    @Test
    void не_cdbx_отвергается() {
        assertThatThrownBy(() -> CdbxParser.parse("просто текст".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
