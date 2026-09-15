package com.example.scriptcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Скрипт видит значения уже в типе колонки, а пустые — подменёнными default_value. */
    @Test
    void parseConvertsTypesAndAppliesDefaults() throws Exception {
        ProjectData data = ProjectData.parse(MAPPER.readTree("""
                {"tables":[{"name":"solutions",
                  "columns":[{"name":"density","value_type":"float"},
                             {"name":"tank","value_type":"int"},
                             {"name":"note","value_type":"string","default_value":"нет"}],
                  "rows":[{"key":"ALK","values":{"density":1.32,"tank":3}}]}]}
                """));

        Map<String, Object> row = data.table("solutions").rowsByKey().get("ALK");

        assertEquals("ALK", row.get("key"));
        assertEquals(1.32, row.get("density"));
        assertEquals(3L, row.get("tank"));
        assertEquals("нет", row.get("note"));
    }

    /** Значение не того типа editor обязан отклонить при сохранении — приведение это различает. */
    @Test
    void convertRejectsWrongType() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectDataValues.convert(TextNode.valueOf("плотная"), "float"));
    }
}
