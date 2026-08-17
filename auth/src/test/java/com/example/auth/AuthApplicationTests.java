package com.example.auth;

import com.example.auth.support.PostgresTestContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AuthApplicationTests extends PostgresTestContainerSupport {

    @Test
    void contextLoads() {
    }
}
