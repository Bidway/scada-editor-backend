package com.example.auth.controller;

import com.example.auth.JwtService;
import com.example.auth.support.PostgresTestContainerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIT extends PostgresTestContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    // ─── register ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void register_newUser_returns200AndValidJwt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "integrationUser", "password", "pass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Registered successfully"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        assertThatCode(() -> {
            var claims = jwtService.extractClaims(token);
            assertThat(claims.getSubject()).isEqualTo("integrationUser");
        }).doesNotThrowAnyException();
    }

    @Test
    @Order(2)
    void register_sameLoginAgain_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Пароль обязан пройти @Size(min = 6): иначе запрос отобьёт Bean Validation,
                        // и до проверки «такой логин уже есть» дело не дойдёт — тест проверял бы
                        // валидацию вместо занятого логина (после включения валидации так и было).
                        .content(objectMapper.writeValueAsString(Map.of("login", "integrationUser", "password", "otherPass"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User exists"));
    }

    // ─── login ────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void login_correctCredentials_returns200AndValidJwt() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "integrationUser", "password", "pass123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Logged in successfully"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();

        assertThatCode(() -> {
            var claims = jwtService.extractClaims(token);
            assertThat(claims.getSubject()).isEqualTo("integrationUser");
        }).doesNotThrowAnyException();
    }

    @Test
    @Order(4)
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "integrationUser", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void login_unknownLogin_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "nobody", "password", "x"))))
                .andExpect(status().isUnauthorized());
    }

    // ─── end-to-end: register → login ─────────────────────────────────────────

    @Test
    @Order(6)
    void registerThenLogin_endToEnd_returnsTokenBothTimes() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "e2eUser", "password", "e2ePass"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "e2eUser", "password", "e2ePass"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }
}
