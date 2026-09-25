package com.example.editor.controller;

import com.example.editor.client.ChannelClient;
import com.example.editor.client.ChannelTree;
import com.example.editor.client.ChannelUnavailableException;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.support.EditorApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Автопривязка: совпавшее свойство перезаписывается, несовпавшее остаётся и идёт в отчёт,
 * изменённая сцена получает версию. channel подменён — его ответ задаёт тест.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutobindIT extends EditorApiTestSupport {

    private static final String ROOT = "Площадка.Test";

    @MockitoBean
    private ChannelClient channelClient;

    @Autowired
    private ComponentRepository componentRepository;

    @Autowired
    private ComponentPropertyRepository propertyRepository;

    @BeforeEach
    void channel() {
        when(channelClient.fetchTree(anyString())).thenReturn(new ChannelTree(
                List.of(ROOT + ".LINE1", ROOT + ".LINE1.V0", ROOT + ".LINE1.V0.ST", ROOT + ".LINE1.V0.M"),
                List.of(new ChannelTree.Param(ROOT + ".LINE1.V0.ST", "Имя в ПЛК", "LINE1V0.ST"),
                        new ChannelTree.Param(ROOT + ".LINE1.V0.M", "Имя в ПЛК", "LINE1V0.M"))));
    }

    private Component component(long sceneId, String name) {
        Component component = new Component();
        component.setName(name);
        component.setType("valve");
        component.setParent(componentRepository.findById(sceneId).orElseThrow());
        return componentRepository.save(component);
    }

    private ComponentProperty property(Component component, String name, String type, String tagId) {
        ComponentProperty property = new ComponentProperty();
        property.setComponent(component);
        property.setName(name);
        property.setPropertyType(type);
        property.setValueType("integer");
        property.setLogging(false);
        property.setTagId(tagId);
        return propertyRepository.save(property);
    }

    private JsonNode autobind(long projectId) throws Exception {
        String body = mockMvc.perform(post("/api/editor/projects/" + projectId + "/autobind")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel_root\":\"" + ROOT + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String tagOf(ComponentProperty property) {
        return propertyRepository.findById(property.getId()).orElseThrow().getTagId();
    }

    @Test
    void совпавшее_перезаписывается_несовпавшее_остаётся_и_в_отчёте() throws Exception {
        long projectId = createProject("autobind-" + System.nanoTime());
        long sceneId = createScene("Схема", projectId);
        Component valve = component(sceneId, "LINE1V0");
        ComponentProperty st = property(valve, "ST", "Тег", "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST");
        ComponentProperty m = property(valve, "M", "Тег", null);
        ComponentProperty onTime = property(valve, "P_ON_TIME", "Тег", "старый.тег");
        ComponentProperty local = property(valve, "ST_LOCAL", "Локальный", null);
        Component label = component(sceneId, "Переключаемый клапан");
        ComponentProperty labelSt = property(label, "ST", "Тег", "ручной.тег");
        ComponentProperty variable = property(label, "ALARM", "Тег", "@var.ALARM_LINE1");
        // У свежей сцены версий ещё нет — считаем нулём.
        Integer current = currentVersion(sceneId, "scenes");
        int before = current == null ? 0 : current;

        JsonNode report = autobind(projectId);

        assertThat(tagOf(st)).isEqualTo(ROOT + ".LINE1.V0.ST");
        assertThat(tagOf(m)).isEqualTo(ROOT + ".LINE1.V0.M");
        assertThat(tagOf(onTime)).isEqualTo("старый.тег");
        assertThat(tagOf(local)).isNull();
        assertThat(tagOf(labelSt)).isEqualTo("ручной.тег");
        assertThat(tagOf(variable)).isEqualTo("@var.ALARM_LINE1");

        assertThat(report.get("bound").asInt()).isEqualTo(2);
        assertThat(report.get("changed").asInt()).isEqualTo(2);
        assertThat(report.get("missing_fields").get(0).get("property").asText()).isEqualTo("P_ON_TIME");
        assertThat(report.get("not_found").get(0).get("name").asText()).isEqualTo("Переключаемый клапан");
        assertThat(report.get("in_operation").asBoolean()).isFalse();
        assertThat(report.get("scenes").get(0).get("scene_id").asLong()).isEqualTo(sceneId);
        assertThat(currentVersion(sceneId, "scenes")).isEqualTo(before + 1);
    }

    /**
     * scada-w7gh, стенд 22.09.2026: FQT1 на Карта1 был привязан к счётчику линии LINE1FQT1, а по
     * старому имени FQT1 нашёлся станционный STATION.FQT1 — все теги молча перетирались.
     */
    @Test
    void привязка_к_другому_устройству_не_перетирается_и_идёт_в_kept() throws Exception {
        when(channelClient.fetchTree(anyString())).thenReturn(new ChannelTree(
                List.of(ROOT + ".STATION", ROOT + ".STATION.FQT1", ROOT + ".STATION.FQT1.F"),
                List.of(new ChannelTree.Param(ROOT + ".STATION.FQT1.F", "Имя в ПЛК", "FQT1.F"))));
        long projectId = createProject("autobind-" + System.nanoTime());
        long sceneId = createScene("Схема", projectId);
        ComponentProperty f = property(component(sceneId, "FQT1"), "F", "Тег",
                "Барановичи-1.BN1_MCA1.V_ST_1.LINE1FQT1.F");

        JsonNode report = autobind(projectId);

        assertThat(tagOf(f)).isEqualTo("Барановичи-1.BN1_MCA1.V_ST_1.LINE1FQT1.F");
        assertThat(report.get("changed").asInt()).isZero();
        assertThat(report.get("kept").get(0).get("current").asText())
                .isEqualTo("Барановичи-1.BN1_MCA1.V_ST_1.LINE1FQT1.F");
        assertThat(report.get("kept").get(0).get("found").asText()).isEqualTo(ROOT + ".STATION.FQT1.F");
    }

    @Test
    void повторная_автопривязка_ничего_не_меняет_и_версию_не_пишет() throws Exception {
        long projectId = createProject("autobind-" + System.nanoTime());
        long sceneId = createScene("Схема", projectId);
        property(component(sceneId, "LINE1.V0"), "ST", "Тег", null);
        autobind(projectId);
        int after = currentVersion(sceneId, "scenes");

        JsonNode report = autobind(projectId);

        assertThat(report.get("bound").asInt()).isEqualTo(1);
        assertThat(report.get("changed").asInt()).isZero();
        assertThat(report.get("scenes")).isEmpty();
        assertThat(currentVersion(sceneId, "scenes")).isEqualTo(after);
    }

    @Test
    void channel_недоступен_503_и_ничего_не_записано() throws Exception {
        long projectId = createProject("autobind-" + System.nanoTime());
        long sceneId = createScene("Схема", projectId);
        ComponentProperty st = property(component(sceneId, "LINE1V0"), "ST", "Тег", "прежний.тег");
        when(channelClient.fetchTree(anyString()))
                .thenThrow(new ChannelUnavailableException("нет связи", new RuntimeException()));

        mockMvc.perform(post("/api/editor/projects/" + projectId + "/autobind")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel_root\":\"" + ROOT + "\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(tagOf(st)).isEqualTo("прежний.тег");
    }
}
