package com.example.editor.controller;

import com.example.editor.service.RuntimeProjectsPublisher;
import com.example.editor.support.EditorApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Запись в runtime.projects уходит только после коммита: runtime по ней сразу читает флаг из
 * editor, и незакоммиченный prod он не увидел бы — смена выпуска ждала бы ресинка (до минуты).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuntimePublishAfterCommitIT extends EditorApiTestSupport {

    @MockitoBean
    RuntimeProjectsPublisher publisher;

    @Test
    void смена_prod_и_флага_публикуется_вне_транзакции() throws Exception {
        List<Boolean> inTransaction = new ArrayList<>();
        doAnswer(inv -> inTransaction.add(TransactionSynchronizationManager.isActualTransactionActive()))
                .when(publisher).publish(any(), anyBoolean(), any());
        long projectId = createProject("Публикация");
        mockMvc.perform(post("/api/editor/projects/" + projectId + "/versions")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/editor/projects/" + projectId + "/runtime/prod")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"versionNo\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/editor/projects/" + projectId + "/runtime")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Username", USER)
                        .content("{\"inOperation\":true}"))
                .andExpect(status().isOk());

        assertThat(inTransaction).containsExactly(false, false);
    }
}
