package com.example.runtime.ws;

import com.example.runtime.config.RuntimeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Личность подключающегося на этапе handshake. Подпись токена проверяет gateway — он же
 * единственный вход в контур (порты сервисов наружу не публикуются), поэтому сюда доходит уже
 * проверенный запрос с заголовками {@code X-User-Id}/{@code X-Username}. Собственного разбора JWT
 * в runtime больше нет: это снимало бы ту же подпись вторым секретом в третьем сервисе.
 * <p>
 * Аутентификация выполняется один раз на подключение, а не на каждый кадр, поэтому горячий путь
 * потока тегов не затрагивается.
 * <p>
 * Отключается флагом {@code runtime.ws.require-auth} — для локального прогона runtime без gateway,
 * когда заголовки проставить некому.
 */
@Component
@Slf4j
public class RuntimeHandshakeInterceptor implements HandshakeInterceptor {

    /** Проставляются gateway после проверки токена; клиентские значения он затирает. */
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USERNAME_HEADER = "X-Username";

    public static final String USER_ID_ATTRIBUTE = "userId";
    public static final String USERNAME_ATTRIBUTE = "username";

    private final boolean requireAuth;

    public RuntimeHandshakeInterceptor(RuntimeProperties properties) {
        this.requireAuth = properties.getWs().isRequireAuth();
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String username = request.getHeaders().getFirst(USERNAME_HEADER);
        String userId = request.getHeaders().getFirst(USER_ID_HEADER);

        if (username == null || username.isBlank()) {
            if (requireAuth) {
                // Либо запрос пришёл мимо gateway, либо gateway не проставил заголовки.
                // Оба случая — отказ: иначе сессия оператора осталась бы без владельца.
                log.warn("WebSocket handshake rejected: no authenticated user ({})",
                        request.getURI().getPath());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            return true;
        }

        attributes.put(USERNAME_ATTRIBUTE, username);
        if (userId != null && !userId.isBlank()) {
            attributes.put(USER_ID_ATTRIBUTE, userId);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Ничего: результат уже определён в beforeHandshake.
    }
}
