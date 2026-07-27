package com.example.runtime.ws;

import com.example.runtime.config.RuntimeProperties;
import com.example.runtime.security.JwtService;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Аутентификация WebSocket на этапе handshake. Браузерный WebSocket не умеет слать заголовок
 * {@code Authorization}, поэтому токен ожидается в query: {@code /ws/runtime/{sessionId}?token=<jwt>}.
 * Проверка — тем же секретом, что и в gateway (см. {@link JwtService}); аутентификация один раз
 * на подключение, а не на каждый фрейм, поэтому горячий путь потока тегов не страдает.
 * <p>
 * Включается флагом {@code runtime.ws.require-auth} (по умолчанию {@code true}). Отключение
 * оставлено на случай локального прогона/стенда, где фронт ещё не передаёт токен.
 */
@Component
@Slf4j
public class RuntimeHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;
    private final boolean requireAuth;

    public RuntimeHandshakeInterceptor(JwtService jwtService, RuntimeProperties properties) {
        this.jwtService = jwtService;
        this.requireAuth = properties.getWs().isRequireAuth();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!requireAuth) {
            return true;
        }
        String path = request.getURI().getPath();
        String token = tokenFromQuery(request);
        if (token == null) {
            log.warn("WebSocket handshake rejected: missing token ({})", path);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Claims claims = jwtService.extractClaims(token);
            attributes.put("userId", claims.get("userId"));
            attributes.put("username", claims.getSubject());
            return true;
        } catch (Exception e) {
            log.warn("WebSocket handshake rejected: invalid token ({}): {}", path, e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Ничего: результат уже определён в beforeHandshake.
    }

    private String tokenFromQuery(ServerHttpRequest request) {
        MultiValueMap<String, String> params =
                UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String token = params.getFirst("token");
        return (token == null || token.isBlank()) ? null : token;
    }
}
