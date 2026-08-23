package com.reservex.lua;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GrabLuaContractTest {

    @Test
    void occupyRecoveryEvidenceHasNoTtl() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("lua/grab.lua")) {
            assertThat(input).isNotNull();
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(script).doesNotContain("redis.call('EXPIRE', 'occupy:'");
            assertThat(script).contains("'id_card_hash', ARGV[10]");
        }
    }
}
