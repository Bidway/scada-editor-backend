package com.example.gateway.filter;

import com.example.gateway.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Единственная проверка личности в системе (scada-gye): сервисы за gateway верят заголовкам
 * X-User-Id/X-Username. Если фильтр перестанет их затирать или начнёт пропускать запросы без
 * токена, любой клиент сможет представиться кем угодно — и ни один тест раньше этого не ловил.
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-42";

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(new JwtService(SECRET));

    /** Цепочка, запоминающая запрос, который ушёл бы в сервис. */
    private final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange.getRequest());
        return Mono.empty();
    };

    private static String token(String secret, Long userId, String username) {
        return Jwts.builder()
                .setSubject(username)
                .claim("userId", userId)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                .compact();
    }

    @Test
    void validToken_overwritesIdentityHeadersSentByClient() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/editor/components/1")
                .header("Authorization", "Bearer " + token(SECRET, 7L, "ivanov"))
                .header("X-User-Id", "1")
                .header("X-Username", "admin"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get().getHeaders().get("X-User-Id")).containsExactly("7");
        assertThat(forwarded.get().getHeaders().get("X-Username")).containsExactly("ivanov");
    }

    @Test
    void missingOrForeignToken_isRejectedAndNotForwarded() {
        MockServerWebExchange noToken = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/editor/components/1")
                .header("X-Username", "admin"));
        MockServerWebExchange foreign = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/editor/components/1")
                .header("Authorization", "Bearer "
                        + token("other-secret-other-secret-other-secret-1", 7L, "ivanov")));

        filter.filter(noToken, chain).block();
        filter.filter(foreign, chain).block();

        assertThat(noToken.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(foreign.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    /** Логин идёт без токена; браузерный WebSocket монитора не умеет Authorization — токен в query. */
    @Test
    void authPathPassesWithoutToken_andRuntimeWebSocketTakesTokenFromQuery() {
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")), chain).block();
        assertThat(forwarded.get().getURI().getPath()).isEqualTo("/api/auth/login");

        filter.filter(MockServerWebExchange.from(MockServerHttpRequest
                .get("/ws/runtime/runtime-1/sessions/abc")
                .queryParam("token", token(SECRET, 9L, "petrov"))), chain).block();
        assertThat(forwarded.get().getHeaders().get("X-Username")).containsExactly("petrov");
    }
}
