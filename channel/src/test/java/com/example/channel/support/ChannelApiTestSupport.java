package com.example.channel.support;

import com.example.channel.config.command.CommandLog;
import com.example.channel.config.command.CommandLogRepository;
import com.example.channel.dto.nodeDto.CreateNodeDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Comparator;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Хелперы поверх MockMvc для тестов channel.
 * <p>
 * X-Username проставляет gateway, downstream его только читает — в тестах подставляем сами.
 * <p>
 * Поле type в CreateNodeDto ссылается на шаблон: NodeServiceImpl ищет его через
 * templateRepository.findByIdWithParams и при отсутствии просто не создаёт параметров
 * (orElse(List.of())). Поэтому для тестов отмены узла подойдёт любое значение — шаблоны
 * заводить не нужно.
 */
public abstract class ChannelApiTestSupport extends PostgresTestContainerSupport {

    protected static final String USER = "tester";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected CommandLogRepository commandLogRepository;

    protected JsonNode createNode(String idNode, long type) throws Exception {
        CreateNodeDto dto = new CreateNodeDto();
        dto.setIdNode(idNode);
        dto.setType(type);

        String body = mockMvc.perform(post("/api/channel/node")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected JsonNode deleteNode(String idNode) throws Exception {
        String body = mockMvc.perform(delete("/api/channel/node/" + idNode)
                        .header("X-Username", USER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Ответ POST /api/channel/undo — список id, отмена которых не удалась. */
    protected List<Long> undo(List<Long> logIds) throws Exception {
        String ids = logIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String body = mockMvc.perform(post("/api/channel/undo")
                        .header("X-Username", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[" + ids + "]"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, new TypeReference<List<Long>>() {});
    }

    /** id записей журнала, новые первыми. */
    protected List<Long> logIdsDesc() {
        return commandLogRepository.findAll().stream()
                .map(CommandLog::getId)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    protected long lastLogId() {
        return logIdsDesc().stream().findFirst()
                .orElseThrow(() -> new AssertionError("command_log пуст: команда не записалась"));
    }
}
