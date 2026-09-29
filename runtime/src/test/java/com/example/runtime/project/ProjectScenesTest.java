package com.example.runtime.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Сцены монитору — из сырого дерева выпуска, со всеми полями, которых нет в DTO runtime. */
class ProjectScenesTest {

    private static final String TREE = """
            {"id":1,"type":"project","name":"П","children":[
              {"id":5,"type":"scene","name":"Главная","parent_id":1,"meta":{"bg":"#fff"},
               "children":[{"id":7,"type":"rect","x":10}]},
              {"id":6,"type":"scene","name":"Танки","parent_id":1,"children":[]},
              {"id":8,"type":"folder","name":"не сцена"}]}
            """;

    @Test
    void список_сцен_и_сцена_целиком_с_полями_фронта() throws Exception {
        JsonNode tree = new ObjectMapper().readTree(TREE);

        assertThat(ProjectScenes.list(tree)).extracting(m -> m.get("id")).containsExactly(5L, 6L);
        assertThat(ProjectScenes.list(tree).get(0)).containsEntry("project_id", 1L).containsEntry("name", "Главная");

        JsonNode scene = ProjectScenes.find(tree, 5).orElseThrow();
        assertThat(scene.path("meta").path("bg").asText()).isEqualTo("#fff");
        assertThat(scene.path("children").get(0).path("x").asInt()).isEqualTo(10);
        assertThat(ProjectScenes.find(tree, 8)).isEmpty();
        assertThat(ProjectScenes.find(tree, 99)).isEmpty();
    }
}
