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
    void незнакомое_поле_получает_FLOAT_с_пометкой() {
        assertThat(DataTypeResolver.resolve("ST_CH[ 1 ]")).isEqualTo(new DataTypeResolver.Resolved("FLOAT", true));
    }
}
