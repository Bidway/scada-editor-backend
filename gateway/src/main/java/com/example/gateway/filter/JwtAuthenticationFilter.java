package com.example.gateway.filter;

import com.example.gateway.service.JwtService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        // Пропускаем login/register
        if (path.startsWith("/api/auth")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtService.extractClaims(token);

            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();

            if (userId == null || username == null) {
                // Токен валиден, но обязательных claim'ов нет — это баг эмитента токена,
                // а не рядовая неудачная аутентификация. Раньше на этом был молчаливый NPE.
                log.warn("JWT missing required claims for path {}: userId={}, subject={}",
                        path, userId, username);
                return unauthorized(exchange);
            }

            ServerHttpRequest mutatedRequest = exchange.getRequest()
                    .mutate()
                    .header("X-User-Id", userId.toString())
                    .header("X-Username", username)
                    .build();

            return chain.filter(exchange.mutate()
                    .request(mutatedRequest)
                    .build());

        } catch (Exception e) {
            // Обычная неудачная аутентификация (протухший/битый токен) — debug, чтобы не спамить;
            // но след в логе теперь есть, а не как раньше — глухой 401.
            log.debug("JWT rejected for path {}: {}", path, e.getMessage());
            return unauthorized(exchange);
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}