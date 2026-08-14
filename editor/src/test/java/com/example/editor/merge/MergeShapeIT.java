package com.example.editor.merge;

import com.example.editor.dto.component.ComponentCreateDto;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Приведение снимка к DTO сохранения обязано быть без потерь.
 * <p>
 * Слияние сравнивает три дерева одного типа: база и чужое приезжают из снимков (это
 * сериализованный {@code ComponentResponseDto}), моё — из тела запроса ({@code
 * ComponentCreateDto}). Если приведение теряет поле, слияние увидит на его месте изменение —
 * и посыплет ложными конфликтами во всех остальных тестах сразу. Поэтому тест берёт настоящий
 * снимок из базы, а не написанный руками: руками я напишу ровно те поля, о которых помню.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MergeShapeIT extends EditorApiTestSupport {

    /** Компонент со всеми шестью видами вложенных сущностей сразу. */
    private String fullComponent(long sceneId) {
        return "[{\"name\":\"Насос\",\"type\":\"valve\",\"parent_id\":" + sceneId + ","
                + "\"properties\":[{\"name\":\"Уставка\",\"value_type\":\"double\","
                + "\"property_type\":\"Тег\",\"default_value\":\"10\",\"description\":\"опис\","
                + "\"tag_id\":\"Ц.У.Т.ST\",\"position\":0,\"logging\":true,"
                + "\"onChange\":\"return 1;\"}],"
                + "\"scripts\":[{\"name\":\"Открыть\",\"script\":\"return 1;\"}],"
                + "\"states\":[{\"name\":\"Норма\",\"image\":{\"x\":1},\"isDefault\":true}],"
                + "\"events\":[{\"event_type\":\"onClick\",\"script\":\"a()\"}],"
                + "\"bindings\":[{\"component_property_name\":\"Уставка\",\"name\":\"цвет\","
                + "\"script\":\"{}\"}]}]";
    }

    @Test
    void snapshotSurvivesConversionToSaveDto() throws Exception {
        long sceneId = newScene();
        saveComponents(fullComponent(sceneId));
        JsonNode snapshot = getJson("/api/editor/scenes/" + sceneId + "/versions/1");

        List<ComponentCreateDto> dtos = MergeShape.childrenOf(snapshot, objectMapper);
        JsonNode back = objectMapper.valueToTree(dtos);

        assertThat(MergeShape.canonical(back))
                .as("поле, потерянное при приведении, слияние примет за изменение")
                .isEqualTo(MergeShape.canonical(snapshot.get("children")));
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
