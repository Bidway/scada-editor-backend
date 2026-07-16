package com.example.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET =
            "f8d7e2c6a1b3f9e5d8c4a7b2e1f3c5d6a8b4e7f2c9d1a3b5e6f8c2d4e7f9a0b1";
    private static final long EXPIRATION_MS = 3_600_000L;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION_MS);
    }

    @Test
    void generateToken_returnsThreePartJwtString() {
        String token = jwtService.generateToken("alice", 1L);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void extractClaims_subjectEqualsLogin() {
        String token = jwtService.generateToken("alice", 1L);
        Claims claims = jwtService.extractClaims(token);

        assertThat(claims.getSubject()).isEqualTo("alice");
    }

    @Test
    void extractClaims_userIdClaimEqualsGivenId() {
        String token = jwtService.generateToken("bob", 42L);
        Claims claims = jwtService.extractClaims(token);

        assertThat(claims.get("userId", Long.class)).isEqualTo(42L);
    }

    @Test
    void extractClaims_tokenExpirationIsInFuture() {
        String token = jwtService.generateToken("charlie", 7L);
        Claims claims = jwtService.extractClaims(token);

        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void extractClaims_invalidSignature_throwsJwtException() {
        String token = jwtService.generateToken("dave", 99L);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";

        assertThatThrownBy(() -> jwtService.extractClaims(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void extractClaims_randomGarbage_throwsJwtException() {
        assertThatThrownBy(() -> jwtService.extractClaims("not.a.token"))
                .isInstanceOf(JwtException.class);
    }
}
