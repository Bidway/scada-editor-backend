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

/**
 * Единственная точка проверки подписи токена в системе: сервисы за gateway не разбирают JWT
 * и принимают личность из заголовков {@code X-User-Id}/{@code X-Username}. Это верно только
 * потому, что их порты больше не публикуются наружу (см. docker-compose) — снаружи в контур
 * можно попасть лишь через этот фильтр, а заголовки он проставляет сам, затирая присланные
 * клиентом.
 * <p>
 * Проверка стоит на HTTP-запросе, в том числе на запросе апгрейда WebSocket, — то есть один раз
 * на подключение, а не на каждый кадр телеметрии. Дальше gateway кадры только пересылает.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** Логин/регистрация — до выдачи токена проверять нечего. */
    private static final String AUTH_PREFIX = "/api/auth";

    /** WebSocket мониторинга (raw, без STOMP). */
    private static final String RUNTIME_WS_PREFIX = "/ws/runtime/";

    /** STOMP-эндпоинт канала. */
    private static final String WS_PREFIX = "/ws";

    /**
     * Браузерный WebSocket не умеет слать заголовок {@code Authorization} на апгрейде,
     * поэтому для WS токен принимается из query. Только для WS: для обычных запросов это
     * означало бы токен в access-логах и в истории браузера.
     */
    private static final String WS_TOKEN_PARAM = "token";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                             org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        if (path.startsWith(AUTH_PREFIX)) {
            return chain.filter(exchange);
        }

        // STOMP канала проксируется, но пока не аутентифицируется: у него другой клиент
        // (SockJS) и своя схема передачи токена, это отдельный шаг плана (B7). Экспозиция
        // при этом не выросла — раньше тот же эндпоинт был доступен напрямую на :8082.
        if (isChannelWebSocket(path)) {
            return chain.filter(exchange);
        }

        String token = path.startsWith(RUNTIME_WS_PREFIX)
                ? exchange.getRequest().getQueryParams().getFirst(WS_TOKEN_PARAM)
                : bearerToken(exchange);

        if (token == null || token.isBlank()) {
            return unauthorized(exchange);
        }

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

            // header() именно заменяет значение, а не добавляет: присланный клиентом
            // X-Username затирается, подделать личность через заголовок нельзя.
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

    /** {@code /ws/...}, но не {@code /ws/runtime/...}. */
    private static boolean isChannelWebSocket(String path) {
        return (path.equals(WS_PREFIX) || path.startsWith(WS_PREFIX + "/"))
                && !path.startsWith(RUNTIME_WS_PREFIX);
    }

    private String bearerToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);
        return (authHeader != null && authHeader.startsWith("Bearer ")) ? authHeader.substring(7) : null;
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
