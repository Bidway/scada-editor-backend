package com.example.editor.controller;

import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * У свойства три разных имени: name — адрес для скриптов (OP.V, writeTag('OP')), label —
 * человеческое имя для оператора («Опции» монитора), gateway_name — имя для шлюза (бывшее
 * description). Пока фронт не перешёл, он шлёт description и читает его же — оба пути должны
 * работать, иначе сохранение сцены из старого фронта молча сотрёт имя для шлюза.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PropertyLabelIT extends EditorApiTestSupport {

    private String component(long sceneId, String property) {
        return "[{\"name\":\"Линия GR1\",\"type\":\"text\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[" + property + "]}]";
    }

    @Test
    void labelAndGatewayName_roundTrip_andDescriptionMirrorsGatewayName() throws Exception {
        long sceneId = newScene();
        JsonNode prop = saveComponents(component(sceneId,
                "{\"name\":\"OP\",\"label\":\"F4 Текущий приемник, № танка\","
                        + "\"gateway_name\":\"OBJECT10.S_PAR_F[ 4 ]\",\"value_type\":\"integer\",\"property_type\":\"Тег\"}"))
                .get(0).get("properties").get(0);

        assertThat(prop.get("name").asText()).isEqualTo("OP");
        assertThat(prop.get("label").asText()).isEqualTo("F4 Текущий приемник, № танка");
        assertThat(prop.get("gateway_name").asText()).isEqualTo("OBJECT10.S_PAR_F[ 4 ]");
        assertThat(prop.get("description").asText())
                .as("старый фронт читает description — до перехода отдаём то же значение")
                .isEqualTo("OBJECT10.S_PAR_F[ 4 ]");
    }

    @Test
    void legacyDescriptionOnly_landsInGatewayName() throws Exception {
        long sceneId = newScene();
        JsonNode prop = saveComponents(component(sceneId,
                "{\"name\":\"OP\",\"description\":\"OBJECT10.CMD\",\"value_type\":\"integer\",\"property_type\":\"Тег\"}"))
                .get(0).get("properties").get(0);

        assertThat(prop.get("gateway_name").asText())
                .as("тело старого фронта и старые версии сцен несут description")
                .isEqualTo("OBJECT10.CMD");
        assertThat(prop.get("label").isNull()).isTrue();
    }
}
