package com.example.runtime.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Инспектор объектов: все объекты выпуска со свойствами, со сценой, по имени. */
class ProjectObjectsTest {

    private static final String TREE = """
            {"id":1,"type":"project","name":"П","children":[
              {"id":5,"type":"scene","name":"Танки","children":[
                {"id":7,"type":"group","name":"Танк 1","children":[
                  {"id":9,"type":"rect","name":"TANK1LT1","properties":[
                    {"id":21,"name":"V","position":null},
                    {"id":20,"name":"CLEVEL","label":"Уровень","tag_id":"Б.П.TANK1LT1.CLEVEL",
                     "value_type":"number","position":1}]}]}]},
              {"id":6,"type":"scene","name":"Линия","children":[
                {"id":8,"type":"rect","name":"LineInObj2","properties":[{"id":30,"name":"M"}]},
                {"id":10,"type":"rect","name":"без свойств","properties":[]}]}]}
            """;

    @Test
    @SuppressWarnings("unchecked")
    void объекты_всех_сцен_по_имени_со_сценой_и_свойствами_по_порядку() throws Exception {
        List<Map<String, Object>> objects = ProjectObjects.list(new ObjectMapper().readTree(TREE));

        assertThat(objects).extracting(o -> o.get("name")).containsExactly("LineInObj2", "TANK1LT1");
        Map<String, Object> tank = objects.get(1);
        assertThat(tank).containsEntry("sceneId", 5L).containsEntry("sceneName", "Танки");
        List<Map<String, Object>> props = (List<Map<String, Object>>) tank.get("properties");
        assertThat(props).extracting(p -> p.get("name")).containsExactly("CLEVEL", "V");
        assertThat(props.get(0)).containsEntry("label", "Уровень").containsEntry("tag_id", "Б.П.TANK1LT1.CLEVEL");
    }
}
