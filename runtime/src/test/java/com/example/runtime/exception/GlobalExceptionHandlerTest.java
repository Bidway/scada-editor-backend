package com.example.runtime.exception;

import com.example.runtime.controller.ProcedureController;
import com.example.runtime.recipe.ProcedureExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ошибка клиента не должна выглядеть сбоем сервиса: общий обработчик Exception превращал
 * пропущенный query-параметр в безликий 500 и стектрейс уровня ERROR в логе (scada-udsc).
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProcedureController(mock(ProcedureExecutionService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void пропущенный_параметр_это_400_с_именем_параметра() throws Exception {
        mockMvc.perform(get("/api/runtime/recipes/r1/status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("projectId")));
    }
}
