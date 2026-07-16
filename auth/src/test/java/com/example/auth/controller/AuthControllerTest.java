package com.example.auth.controller;

import com.example.auth.JwtService;
import com.example.auth.SecurityConfig;
import com.example.auth.model.User;
import com.example.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JwtService jwtService;

    // ─── register ────────────────────────────────────────────────────────────

    @Test
    void register_newUser_returns200WithToken() throws Exception {
        when(userRepository.findByLogin("alice")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(jwtService.generateToken("alice", 1L)).thenReturn("jwt-token");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "alice", "password", "secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.message").value("Registered successfully"));
    }

    @Test
    void register_existingLogin_returns400() throws Exception {
        User existing = new User("alice", "hashed");
        when(userRepository.findByLogin("alice")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "alice", "password", "secret"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("User exists"));
    }

    // ─── login ────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        User user = new User("bob", "hashed");
        user.setId(2L);
        when(userRepository.findByLogin("bob")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(jwtService.generateToken("bob", 2L)).thenReturn("jwt-bob");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "bob", "password", "secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-bob"))
                .andExpect(jsonPath("$.message").value("Logged in successfully"));
    }

    @Test
    void login_unknownLogin_returns401() throws Exception {
        when(userRepository.findByLogin("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "ghost", "password", "x"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        User user = new User("carol", "hashed");
        user.setId(3L);
        when(userRepository.findByLogin("carol")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("login", "carol", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }
}
