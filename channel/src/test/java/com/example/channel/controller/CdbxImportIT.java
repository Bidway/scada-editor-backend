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

import static org.assertj.core.api.Assertions.assertThat;
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
        // UP_TIME и P_T_GEN вне таблицы типов — в порядке файла.
        assertThat(report.get("guessedType")).extracting(JsonNode::asText)
                .containsExactly("SYSTEM.UP_TIME", "LINE1WATCHDOG1.P_T_GEN");
        assertThat(report.get("skipped")).isEmpty();

        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.LINE1.V0.ST")).isPresent();
        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.LINE1.OBJECT.RT_PAR_F[119]")).isPresent();
        assertThat(nodeRepository.findByIdNode("ИМП-1.MCA.STATION.SYSTEM.UP_TIME")).isPresent();
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Имя в ПЛК")).isEqualTo("LINE1V0.ST");
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Тип данных")).isEqualTo("INT32");
        assertThat(param("ИМП-1.MCA.LINE1.V0.ST", "Описание")).isEqualTo("Клапан V00");
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
}
