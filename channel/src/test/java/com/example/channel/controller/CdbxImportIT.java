package com.example.channel.controller;

import com.example.channel.repository.NodeRepository;
import com.example.channel.support.ChannelApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Импорт .cdbx создаёт новую объектную базу одной транзакцией и мимо журнала команд —
 * принятое исключение из Command Pattern (спека 2026-09-22-cdbx-import-object-channels).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CdbxImportIT extends ChannelApiTestSupport {

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private JdbcTemplate jdbc;

    protected MockMultipartFile miniFile() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/cdbx/mini.cdbx")) {
            return new MockMultipartFile("file", "mini.cdbx", "application/octet-stream", in.readAllBytes());
        }
    }

    protected JsonNode importMini(String site, String project) throws Exception {
        String body = mockMvc.perform(multipart("/api/channel/import/cdbx").file(miniFile())
                        .param("site", site).param("project", project))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String param(String idNode, String typeName) {
        return jdbc.queryForObject("""
                SELECT p.value FROM channel.param p JOIN channel.description d ON d.id = p.id_type
                WHERE p.id_node = ? AND d.name = ?""", String.class, idNode, typeName);
    }

    @Test
    void импорт_строит_объектное_дерево_со_старым_именем_и_без_журнала() throws Exception {
        long logBefore = commandLogRepository.count();

        JsonNode report = importMini("ИМП-1", "MCA");

        assertThat(report.get("root").asText()).isEqualTo("ИМП-1.MCA");
        assertThat(report.get("nodes").asInt()).isEqualTo(16);
        assertThat(report.get("channels").asInt()).isEqualTo(6);
        assertThat(report.get("merged").get(0).asText()).isEqualTo("OBJECT1.RT_PAR_F[119]");
        // Все поля мини-файла есть в таблице типов с 23.09.2026 (scada-3ebv).
        assertThat(report.get("guessedType")).isEmpty();
        assertThat(report.get("skipped")).isEmpty();

        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.LINE1.V0.ST")).isPresent();
        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.LINE1.OBJECT.RT_PAR_F[119]")).isPresent();
        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.STATION.SYSTEM.UP_TIME")).isPresent();
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Имя в ПЛК")).isEqualTo("LINE1V0.ST");
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Тип данных")).isEqualTo("INT32");
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Описание")).isEqualTo("Клапан V00");
        // Строка, объявленная числом, теряется у шлюза — тип должен доехать до базы именно STRING.
        assertThat(param("ИМП-1.MCA.STATION.SYSTEM.UP_TIME", "Тип данных")).isEqualTo("STRING");
        // Параметры слитого канала — от первого встреченного, «Параметры линии».
        assertThat(param("ИМП-1.MCA.LINE1.OBJECT.RT_PAR_F[119]", "Описание")).isEqualTo("Параметр");
        assertThat(param("ИМП-1.MCA", "Источник импорта")).isEqualTo("mini.cdbx");
        assertThat(param("ИМП-1.MCA", "Имя порта")).isEqualTo("COM3");

        assertThat(commandLogRepository.count()).isEqualTo(logBefore);
    }

    @Test
    void повторный_импорт_в_тот_же_проект_отклоняется() throws Exception {
        importMini("ИМП-2", "MCA");

        mockMvc.perform(multipart("/api/channel/import/cdbx").file(miniFile())
                        .param("site", "ИМП-2").param("project", "MCA"))
                .andExpect(status().isConflict());
    }

    @Test
    void битый_файл_ничего_не_создаёт() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", "broken.cdbx", "application/octet-stream",
                "<?xml version=\"1.0\"?><driver><oops".getBytes());

        mockMvc.perform(multipart("/api/channel/import/cdbx").file(broken)
                        .param("site", "ИМП-3").param("project", "MCA"))
                .andExpect(status().isBadRequest());

        assertThat(nodeRepository.findByIdNode("ИМП-3")).isEmpty();
    }

    @Test
    void удаление_убирает_только_свой_проект() throws Exception {
        importMini("ИМП-4", "BN1_MCA2");
        importMini("ИМП-4", "BN1_MCA20");

        mockMvc.perform(delete("/api/channel/import/ИМП-4/BN1_MCA2"))
                .andExpect(status().isNoContent());

        assertThat(nodeRepository.findByIdNode("ИМП-4.BN1_MCA2")).isEmpty();
        assertThat(nodeRepository.findByIdNode("ИМП-4.BN1_MCA2.LINE1.V0.ST")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM channel.param WHERE id_node LIKE 'ИМП-4.BN1\\_MCA2.%' ESCAPE '\\'",
                Integer.class)).isZero();
        // Общий префикс имени — не повод удалить соседа.
        assertThat(nodeRepository.findByIdNode("ИМП-4.BN1_MCA20.LINE1.V0.ST")).isPresent();
        assertThat(nodeRepository.findByIdNode("ИМП-4")).isPresent();
    }

    /** Базу, собранную руками или залитую дампом (как BN1_MCA1), этим эндпоинтом не снести. */
    @Test
    void проект_без_источника_импорта_не_удаляется() throws Exception {
        // Без parentKey путь берётся как есть (NodeMapper.setIdNode); шаблона 1 нет — узел без параметров.
        createNode("РУЧ", 1L);
        createNode("РУЧ.MCA", 1L);

        mockMvc.perform(delete("/api/channel/import/РУЧ/MCA"))
                .andExpect(status().isConflict());

        assertThat(nodeRepository.findByIdNode("РУЧ.MCA")).isPresent();
    }

    @Test
    void выгрузка_даёт_строку_тега_со_старым_именем_и_id_узла() throws Exception {
        importMini("ИМП-5", "MCA");
        long nodeId = nodeRepository.findByIdNode("ИМП-5.MCA.LINE1.V0.ST").orElseThrow().getId();

        String yaml = mockMvc.perform(get("/api/channel/export/gateway")
                        .param("root", "ИМП-5.MCA").param("controllerId", "ptusa-test")
                        .param("endpoint", "pac://${PTUSA_HOST}:10000"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(yaml).contains("- id: ptusa-test", "endpoint: \"pac://${PTUSA_HOST}:10000\"");
        assertThat(yaml).contains("{name: \"ИМП-5.MCA.LINE1.V0.ST\", nodeId: \"pac:" + nodeId + "\", channelId: "
                + nodeId + ", deviceName: \"LINE1V0\", fieldName: \"ST\", deviceType: \"V\", protocol: pac, "
                + "dataType: INT32, pollingRate: 2000, enabled: true, writable: true}");
        // Контейнеры и объекты — не теги.
        assertThat(yaml).doesNotContain("name: \"ИМП-5.MCA.LINE1.V0\",");
    }
}
