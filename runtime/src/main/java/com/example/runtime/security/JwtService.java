package com.example.runtime.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;

/**
 * Проверка JWT на WS-handshake (см. {@link com.example.runtime.ws.RuntimeHandshakeInterceptor}).
 * Тот же секрет ({@code jwt.secret}) и алгоритм (HMAC-SHA), что и в gateway — токены взаимозаменяемы.
 * REST-эндпоинты runtime по-прежнему за gateway и здесь не проверяются.
 */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
    }

    /** Разбирает и проверяет подпись/срок действия; бросает при невалидном токене. */
    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
