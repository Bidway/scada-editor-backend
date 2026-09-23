package com.example.channel.importer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlcProjectParserTest {

    /** Куски main.io.lua: у CIPV101 тип V (dtype 0), контейнер CIP — по буквам он резался бы как CIPV. */
    private static final String IO_LUA = """
            devices =
                {
                    {
                    name    = 'CIPV101',
                    descr   = 'CIP+ Магистральный (NO)',
                    dtype   = 0,
                    subtype = 13,
                    },
                    {
                    name    = 'MCA4LINE1DI1',
                    descr   = 'Авария шкафа',
                    dtype   = 13,
                    subtype = 0,
                    },
                }
            """;

    /** Куски main.objects.lua: танк 1 и его узел перемешивания — один name_eplan и один номер. */
    private static final String OBJECTS_LUA = """
            objects =
                {
                    {
                    n          = 1,
                    tech_type  = 1,
                    name       = 'Мастер',
                    name_eplan = 'MASTER',
                    name_BC    = 'MasterObj',
                    cooper_param_number = -1,
                    base_tech_object = 'master',
                    },
                    {
                    n          = 1,
                    tech_type  = 2,
                    name       = 'Танк',
                    name_eplan = 'TANK',
                    name_BC    = 'TankObj1',
                    cooper_param_number = -1,
                    base_tech_object = 'tank',
                    },
                    {
                    n          = 1,
                    tech_type  = 2,
                    name       = 'Узел перемешивания Танк 1',
                    name_eplan = 'TANK',
                    name_BC    = 'MIXObjTank1',
                    cooper_param_number = -1,
                    base_tech_object = 'mix_node',
                    },
                }
            """;

    private static PlcProject parse() {
        return PlcProjectParser.parse(IO_LUA.getBytes(StandardCharsets.UTF_8),
                OBJECTS_LUA.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void прибор_делится_по_типу_из_dtype_а_не_по_буквам() {
        PlcProject plc = parse();
        assertThat(plc.device("CIPV101"))
                .contains(new PlcProject.Device("CIP", "V101", "CIP+ Магистральный (NO)"));
        assertThat(ObjectPathMapper.objectSegments("CIPV101", plc)).isEqualTo(List.of("CIP", "V101"));
        assertThat(ObjectPathMapper.objectSegments("MCA4LINE1DI1", plc))
                .isEqualTo(List.of("MCA4LINE1", "DI1"));
    }

    @Test
    void танк_и_его_узел_перемешивания_не_сливаются_в_один_узел() {
        PlcProject plc = parse();
        assertThat(ObjectPathMapper.objectSegments("OBJECT2", plc)).isEqualTo(List.of("TANK1", "TANK"));
        assertThat(ObjectPathMapper.objectSegments("OBJECT3", plc)).isEqualTo(List.of("TANK1", "MIX_NODE"));
    }

    @Test
    void объекта_сверх_вложения_нет_в_справочнике_и_он_раскладывается_запасным_правилом() {
        PlcProject plc = parse();
        assertThat(ObjectPathMapper.known("OBJECT6", plc)).isFalse();
        assertThat(ObjectPathMapper.objectSegments("OBJECT6", plc)).isEqualTo(List.of("LINE6", "OBJECT"));
    }
}
