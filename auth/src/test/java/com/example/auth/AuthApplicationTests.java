package com.example.auth;

import com.example.auth.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AuthApplicationTests extends PostgresTestContainerSupport {

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @Test
    void contextLoads() {
    }
}
