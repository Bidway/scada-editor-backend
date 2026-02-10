package com.example.scadaeditorbackend.service;

import com.example.scadaeditorbackend.model.User;
import com.example.scadaeditorbackend.security.SecurityUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class LockServiceImplTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private LockService lockService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory()
                .getConnection()
                .flushAll();
    }

    private Authentication auth(Long userId) {
        User user = new User("user" + userId, "password");
        user.setId(userId);

        SecurityUser securityUser = new SecurityUser(user);

        return new UsernamePasswordAuthenticationToken(
                securityUser,
                null,
                securityUser.getAuthorities()
        );
    }


    @Test
    void shouldLockNodesSuccessfully() {
        Authentication auth = auth(1L);

        List<String> result = lockService.tryLock(
                List.of("node1", "node2"),
                auth
        );

        assertEquals(2, result.size());
        assertTrue(result.contains("node1"));
        assertTrue(result.contains("node2"));
    }

    @Test
    void shouldNotLockAlreadyLockedNode() {
        Authentication auth1 = auth(1L);
        Authentication auth2 = auth(2L);

        lockService.tryLock(List.of("node1"), auth1);

        List<String> result = lockService.tryLock(
                List.of("node1"),
                auth2
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldUnlockNodeByOwner() {
        Authentication auth = auth(1L);

        lockService.tryLock(List.of("node1"), auth);

        List<String> result = lockService.unlock(
                List.of("node1"),
                auth
        );

        assertEquals(1, result.size());
        assertNull(redisTemplate.opsForValue().get("node1"));
    }

    @Test
    void shouldNotUnlockNodeByAnotherUser() {
        Authentication auth1 = auth(1L);
        Authentication auth2 = auth(2L);

        lockService.tryLock(List.of("node1"), auth1);

        List<String> result = lockService.unlock(
                List.of("node1"),
                auth2
        );

        assertTrue(result.isEmpty());
        assertEquals("1", redisTemplate.opsForValue().get("node1"));
    }

    @Test
    void shouldNotUnlockNotLockedNode(){
        Authentication auth = auth(1L);

        List<String> result = lockService.unlock(
                List.of("node1"),
                auth
        );
        assertEquals(1, result.size());
    }
}


