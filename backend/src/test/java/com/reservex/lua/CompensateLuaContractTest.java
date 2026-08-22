package com.reservex.lua;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CompensateLuaContractTest {

    @Test
    void persistedBucketIsTheFallbackWhenOccupyWasLost() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("lua/compensate.lua")) {
            assertThat(input).isNotNull();
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(script).contains("local bucketKey = KEYS[1]")
                    .contains("if occupiedBucket and occupiedBucket ~= '' then")
                    .doesNotContain("if not bucketKey then\n        return 0")
                    .contains("redis.call('INCR', bucketKey)")
                    .contains("local recoveryTtl = redis.call('PTTL', ARGV[2])")
                    .contains("redis.call('SET', bucketKey, 1, 'PX', recoveryTtl)")
                    .contains("redis.call('PEXPIRE', bucketKey, recoveryTtl)")
                    .contains("if redis.call('PTTL', bucketKey) < 0 and recoveryTtl <= 0 then")
                    .contains("redis.call('SET', doneKey, '1')");
        }
    }
}
