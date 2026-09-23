package com.example.channel.importer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataTypeResolverTest {

    @Test
    void известные_поля_получают_тип_из_таблицы_шлюза() {
        assertThat(DataTypeResolver.resolve("ST")).isEqualTo(new DataTypeResolver.Resolved("INT32", false));
        assertThat(DataTypeResolver.resolve("CUR_REC")).isEqualTo(new DataTypeResolver.Resolved("STRING", false));
        assertThat(DataTypeResolver.resolve("RT_PAR_F[5]")).isEqualTo(new DataTypeResolver.Resolved("FLOAT", false));
    }

    /** Индекс с пробелами встречается в BN1 MCA1 NEW.cdbx (электрошкаф). */
    @Test
    void индекс_с_пробелами_отбрасывается_вместе_с_обычным() {
        assertThat(DataTypeResolver.resolve("ST_CH[ 1 ]")).isEqualTo(new DataTypeResolver.Resolved("INT32", false));
    }

    @Test
    void незнакомое_поле_получает_FLOAT_с_пометкой() {
        assertThat(DataTypeResolver.resolve("НЕТ_ТАКОГО_ПОЛЯ"))
                .isEqualTo(new DataTypeResolver.Resolved("FLOAT", true));
    }

    /** Номер шага входит в имя поля, а не в индекс: RUN_STEPS1…RUN_STEPS30. */
    @Test
    void шаги_операций_разбираются_образцом_а_не_перечислением() {
        assertThat(DataTypeResolver.resolve("RUN_STEPS2[ 3 ]")).isEqualTo(new DataTypeResolver.Resolved("INT32", false));
        assertThat(DataTypeResolver.resolve("IDLE_STEPS30")).isEqualTo(new DataTypeResolver.Resolved("INT32", false));
        assertThat(DataTypeResolver.resolve("PAUSE_STEPS1")).isEqualTo(new DataTypeResolver.Resolved("INT32", false));
    }

    /**
     * Строка, объявленная числом, теряется у шлюза (PacLua.read вернёт null), поэтому
     * менеджер рецептов и системные строки должны попадать в STRING.
     */
    @Test
    void текстовые_поля_менеджера_рецептов_и_системы_это_строки() {
        assertThat(DataTypeResolver.resolve("NAME")).isEqualTo(new DataTypeResolver.Resolved("STRING", false));
        assertThat(DataTypeResolver.resolve("LIST")).isEqualTo(new DataTypeResolver.Resolved("STRING", false));
        assertThat(DataTypeResolver.resolve("UP_TIME")).isEqualTo(new DataTypeResolver.Resolved("STRING", false));
    }

    /** S_PAR_F — par_float техобъекта (подтверждено в cip_tech_def.cpp), INT32 обрезал бы дробь. */
    @Test
    void параметры_остаются_дробными() {
        assertThat(DataTypeResolver.resolve("S_PAR_F[ 12 ]")).isEqualTo(new DataTypeResolver.Resolved("FLOAT", false));
        assertThat(DataTypeResolver.resolve("P_FB")).isEqualTo(new DataTypeResolver.Resolved("FLOAT", false));
    }
}
