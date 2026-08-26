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

    /**
     * 压测埋点必须有 TTL。裸 {@code INCR} 留下的 key 永不过期,而
     * {@code maxmemory-policy=noeviction} 下它们只增不减(每场次每桶各一条),
     * 是一条没有任何功能表现的内存泄漏 —— 只能靠脚本形态断言。
     */
    @Test
    void stressStatsKeysExpireWithTheSlot() throws IOException {
        String script = readGrab();
        assertThat(script).contains("redis.call('EXPIRE', hitKey, ARGV[14], 'NX')");
        assertThat(script).contains("redis.call('EXPIRE', 'stats:borrow:'..KEYS[i], ARGV[14], 'NX')");
        // NX 而非"仅首次 INCR 时设":后者修不掉修复前遗留的无 TTL 老 key。
        assertThat(script).doesNotContain("if redis.call('INCR', 'stats:");
    }

    /** 借桶上界必须排除末尾两个限流 KEYS,否则限流 key 会被当成库存桶读写。 */
    @Test
    void borrowLoopExcludesTheTwoRateLimitKeys() throws IOException {
        assertThat(readGrab()).contains("for i = 2, n - 2 do");
    }

    private String readGrab() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("lua/grab.lua")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
