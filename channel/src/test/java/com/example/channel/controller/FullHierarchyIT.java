package com.example.channel.controller;

import com.example.channel.model.Description;
import com.example.channel.repository.DescriptionRepository;
import com.example.channel.support.ChannelApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * scada-gye: {@code GET /api/channel/node/fullHierarchy} — то, чем editor забирает базу каналов
 * для автопривязки ({@code ChannelClient.fetchTree}): {@code nodes[].key} всего поддерева и
 * {@code params[]} с {@code parentKey/name/value}. Соседняя база с тем же началом имени
 * ({@code …Test} и {@code …Test4}) в ответ попадать не должна.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FullHierarchyIT extends ChannelApiTestSupport {

    @Autowired
    private DescriptionRepository descriptionRepository;

    @Test
    void returnsWholeSubtreeWithParams_andNotSiblingWithSamePrefix() throws Exception {
        String root = "Site-" + UUID.randomUUID().toString().substring(0, 8) + ".Test";
        createNode(root, 1);
        createNode(root + ".LINE1", 1);
        createNode(root + ".LINE1.V0", 1);
        createNode(root + ".LINE1.V0.ST", 1);
        createNode(root + "4", 1);
        createNode(root + "4.LINE9", 1);

        Description plcName = new Description();
        plcName.setName("Имя в ПЛК");
        plcName.setType("string");
        long descriptionId = descriptionRepository.save(plcName).getId();
        mockMvc.perform(post("/api/channel/param")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentKey\":\"" + root + ".LINE1.V0.ST\",\"id\":" + descriptionId
                                + ",\"value\":\"LINE1V0.ST\"}"))
                .andExpect(status().isOk());

        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/api/channel/node/fullHierarchy")
                        .param("rootPath", root))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> keys = new ArrayList<>();
        body.path("nodes").forEach(node -> keys.add(node.path("key").asText()));
        assertThat(keys).contains(root + ".LINE1", root + ".LINE1.V0", root + ".LINE1.V0.ST")
                .doesNotContain(root + "4", root + "4.LINE9");
        assertThat(body.path("params")).anySatisfy(param -> {
            assertThat(param.path("parentKey").asText()).isEqualTo(root + ".LINE1.V0.ST");
            assertThat(param.path("name").asText()).isEqualTo("Имя в ПЛК");
            assertThat(param.path("value").asText()).isEqualTo("LINE1V0.ST");
        });
    }
}
