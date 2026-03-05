package com.example.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable()) // отключаем CSRF
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll() // все запросы проходят, фильтруем JWT отдельно
                );

        return http.build();
    }
}