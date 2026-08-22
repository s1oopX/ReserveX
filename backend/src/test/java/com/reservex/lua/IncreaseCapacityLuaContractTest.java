package com.reservex.lua;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IncreaseCapacityLuaContractTest {

    @Test
    void missingBucketOrVersionTtlIsRejectedBeforeIncrement() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("lua/incr.lua")) {
            assertThat(input).isNotNull();
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(script.indexOf("if ttl <= 0 then"))
                    .isLessThan(script.indexOf("redis.call('INCRBY'"));
            assertThat(script.indexOf("if redis.call('EXISTS', KEYS[i]) == 0 then"))
                    .isLessThan(script.indexOf("redis.call('INCRBY'"));
        }
    }
}
