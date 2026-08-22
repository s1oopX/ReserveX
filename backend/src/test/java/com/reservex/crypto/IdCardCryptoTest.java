package com.reservex.crypto;

import com.reservex.config.ReserveXProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IdCardCryptoTest {

    @Test
    void keyCacheSupportsConcurrentFirstUse() throws Exception {
        ReserveXProperties props = new ReserveXProperties();
        String idCard = "11010519491231002X";
        List<IdCardCipher.Encrypted> encrypted = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) (i + 1));
            String keyId = "aes-v" + i;
            props.getAes().getKeys().put(keyId, Base64.getEncoder().encodeToString(key));
            props.getAes().setKeyId(keyId);
            encrypted.add(new IdCardCipher(props).encrypt(idCard));
        }

        IdCardCipher cipher = new IdCardCipher(props);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(encrypted.size());
        try {
            List<Future<String>> results = encrypted.stream()
                    .map(value -> executor.submit(() -> {
                        start.await();
                        return cipher.decrypt(value.ciphertext(), value.keyId());
                    }))
                    .toList();
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(idCard);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void usesFreshIvAndStablePepperedHash() {
        ReserveXProperties props = new ReserveXProperties();
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        props.getAes().getKeys().put("aes-v1", Base64.getEncoder().encodeToString(key));
        props.getIdHash().setPepper("test-pepper-with-at-least-32-bytes");

        IdCardCipher cipher = new IdCardCipher(props);
        IdCardHasher hasher = new IdCardHasher(props);
        String idCard = "11010519491231002X";

        IdCardCipher.Encrypted first = cipher.encrypt(idCard);
        IdCardCipher.Encrypted second = cipher.encrypt(idCard);

        assertEquals(46, first.ciphertext().length);
        assertFalse(Arrays.equals(
                Arrays.copyOf(first.ciphertext(), 12),
                Arrays.copyOf(second.ciphertext(), 12)));
        assertEquals(idCard, cipher.decrypt(first.ciphertext(), first.keyId()));
        assertEquals(hasher.hash(idCard), hasher.hash(idCard));
    }

    /**
     * 密钥轮换不变量:解密时按行的 keyId 取对应 key,旧密文用旧 key 仍可解。
     * 没有这个结构,"支持轮换"是空承诺:换 key 后存量密文永久 AEADBadTagException。
     */
    @Test
    void decryptionUsesRowKeyIdForRotation() {
        ReserveXProperties props = new ReserveXProperties();
        byte[] keyV1 = new byte[32];
        Arrays.fill(keyV1, (byte) 1);
        byte[] keyV2 = new byte[32];
        Arrays.fill(keyV2, (byte) 2);
        props.getAes().getKeys().put("aes-v1", Base64.getEncoder().encodeToString(keyV1));
        props.getAes().getKeys().put("aes-v2", Base64.getEncoder().encodeToString(keyV2));
        props.getIdHash().setPepper("test-pepper-with-at-least-32-bytes");

        // 用 v1 加密的存量密文
        props.getAes().setKeyId("aes-v1");
        IdCardCipher cipherV1 = new IdCardCipher(props);
        String idCard = "11010519491231002X";
        IdCardCipher.Encrypted legacy = cipherV1.encrypt(idCard);

        // 轮换:当前加密用 v2,但旧密文仍按其行的 keyId(v1)解密
        props.getAes().setKeyId("aes-v2");
        IdCardCipher cipherV2 = new IdCardCipher(props);

        // 旧密文用旧 keyId 解 → 成功还原明文(不是用当前 v2 去解)
        assertThat(cipherV2.decrypt(legacy.ciphertext(), "aes-v1")).isEqualTo(idCard);

        // 旧密文若误用当前 v2 解 → AEADBadTag(密钥版本错配)
        assertThatThrownBy(() -> cipherV2.decrypt(legacy.ciphertext(), "aes-v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份证解密失败");
    }

    /**
     * pepper 全局固定 → 跨用户可比对:同一身份证无论哪个用户注册都得到相同 hash。
     * 这是 id_card_route 主键 (id_card_hash, slot_date) 能拦"一人一证一天一约"的前提。
     * 若有人误改成 per-row salt,本测试会红(且这正是照密码学教科书做反而错的地方)。
     */
    @Test
    void hashIsPepperedAndCrossUserComparable() {
        ReserveXProperties props = new ReserveXProperties();
        props.getIdHash().setPepper("test-pepper-with-at-least-32-bytes");
        IdCardHasher hasher = new IdCardHasher(props);

        String idCard = "11010519491231002X";
        String h1 = hasher.hash(idCard);
        String h2 = hasher.hash(idCard);

        // 稳定:同一明文同一 pepper 必得同一 hash(确定性,非加盐)
        assertThat(h1).isEqualTo(h2);
        // 64 位小写十六进制(落 CHAR(64))
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");

        // pepper 参与 hash:换 pepper → hash 变(证明 pepper 真的进去了)
        props.getIdHash().setPepper("different-pepper-also-long-enough-1234");
        IdCardHasher hasher2 = new IdCardHasher(props);
        assertThat(hasher2.hash(idCard)).isNotEqualTo(h1);

        // 不同明文 → 不同 hash(基础非碰撞性,非穷举证明)
        assertThat(hasher.hash("110105194912310038")).isNotEqualTo(h1);
    }

    /**
     * 脱敏串:固定 head 3 + 掩码 + tail 4。列表页脱敏展示是高频读,
     * 不应每行解密一次 AES。mask 是从明文算好的冗余串,不由密文派生。
     */
    @Test
    void maskHidesMiddleKeepsHeadAndTail() {
        ReserveXProperties props = new ReserveXProperties();
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        props.getAes().getKeys().put("aes-v1", Base64.getEncoder().encodeToString(key));
        props.getIdHash().setPepper("test-pepper-with-at-least-32-bytes");
        IdCardCipher cipher = new IdCardCipher(props);

        // 18 位身份证:前 3 + 11 个 * + 后 4 = 18
        String masked = cipher.mask("11010519491231002X");
        assertThat(masked).isEqualTo("110***********002X");
        assertThat(masked).hasSize(18);

        // 过短串兜底
        assertThat(cipher.mask("12345")).isEqualTo("****");
        assertThat(cipher.mask(null)).isEqualTo("****");
    }
}
