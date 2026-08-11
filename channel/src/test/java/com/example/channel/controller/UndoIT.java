package com.example.channel.controller;

import com.example.channel.repository.NodeRepository;
import com.example.channel.support.ChannelApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Отмена команд channel. В отличие от editor, здесь Command Pattern остаётся — команды
 * пишут в журнал ровно то, что читают их обработчики, и отмена по одной записи работает.
 * Тесты сторожат это перед правкой оркестрации отмены (scada-6ua).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UndoIT extends ChannelApiTestSupport {

    @Autowired
    private NodeRepository nodeRepository;

    private boolean nodeExists(String idNode) {
        return nodeRepository.findByIdNode(idNode).isPresent();
    }

    @Test
    void undo_ofCreateNode_removesNode() throws Exception {
        createNode("UNDO-CREATE-1", 1L);
        assertThat(nodeExists("UNDO-CREATE-1")).isTrue();

        String failed = undo(List.of(lastLogId()));

        assertThat(failed).isEqualTo("[]");
        assertThat(nodeExists("UNDO-CREATE-1")).isFalse();
    }

    @Test
    void undo_ofDeleteNode_bringsNodeBack() throws Exception {
        createNode("UNDO-DELETE-1", 1L);
        deleteNode("UNDO-DELETE-1");
        assertThat(nodeExists("UNDO-DELETE-1")).isFalse();

        String failed = undo(List.of(lastLogId()));

        assertThat(failed).isEqualTo("[]");
        assertThat(nodeExists("UNDO-DELETE-1")).isTrue();
    }

    /**
     * Повторная отмена той же записи отклоняется: undoneAt уже проставлен. Без этой проверки
     * двойной клик по «отменить» применил бы снимок дважды.
     */
    @Test
    void undo_twice_reportsFailure() throws Exception {
        createNode("UNDO-TWICE-1", 1L);
        long logId = lastLogId();
        undo(List.of(logId));

        String failed = undo(List.of(logId));

        assertThat(failed).contains(String.valueOf(logId));
    }

    /**
     * Создание узла заводит батч; отмена батчем откатывает всю группу разом.
     * batchId возвращается в ответе на создание.
     */
    @Test
    void undoBatch_removesEverythingFromBatch() throws Exception {
        JsonNode created = createNode("UNDO-BATCH-1", 1L);
        UUID batchId = UUID.fromString(created.get("batchId").asText());
        assertThat(nodeExists("UNDO-BATCH-1")).isTrue();

        mockMvc.perform(post("/api/channel/undo/batch/" + batchId)
                        .header("X-Username", USER))
                .andExpect(status().isOk());

        assertThat(nodeExists("UNDO-BATCH-1")).isFalse();
    }
}
