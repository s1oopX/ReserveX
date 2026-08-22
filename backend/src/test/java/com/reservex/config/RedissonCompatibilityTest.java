package com.reservex.config;

import org.junit.jupiter.api.Test;
import org.redisson.spring.data.connection.RedissonConnection;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedissonCompatibilityTest {

    @Test
    void usesSpringData35Adapter() {
        String source = RedissonConnection.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(source.contains("redisson-spring-data-35"), source);
    }
}
