package com.reservex.crypto;

import com.reservex.config.ReserveXProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 身份证密文的唯一读写口(03 §2.1)。
 *
 * <p><b>存储格式:{@code iv(12B) || ciphertext || tag(16B)},整块存 VARBINARY(256)。</b>
 * 18 位身份证 → 12 + 18 + 16 = 46 字节。
 *
 * <p><b>为什么只暴露两个方法、IV 封在里面</b>:GCM 的致命误用是 IV 重用 ——
 * 写死一个常量 IV 会让同一明文得同一密文(GCM 退化成可比对的确定性加密),
 * 并且在 GCM 下 IV 重用会泄露认证密钥流。只要调用方拿不到 IV,就无法误传常量。
 * 这不是封装洁癖,是把一个"能编译过、能跑通、但密码学上已破"的写法从 API 上消除。
 *
 * <p><b>为什么解密要传 keyId</b>:{@code user.id_card_key_id} 记录了该行**加密时**的
 * 密钥版本。没有这一列,轮换到 aes-v2 后存量密文会用 v2 去解 → {@code AEADBadTagException},
 * 而这条路径只在"授权解密"这个低频动作上走,可能上线数月后才被发现。
 *
 * <p><b>本类刻意不提供批量解密</b>:全链路只有注册(写)与授权解密(读)两处接触明文。
 * 抢号/核销/取消/对账一律用 {@code id_card_hash} 与 {@code id_card_masked} ——
 * 若出现"列表页解密一批身份证"的需求,那是设计走偏了,不是本类缺功能。
 */
@Component
@RequiredArgsConstructor
public class IdCardCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    /** AES-256 要求 32 字节密钥。 */
    private static final int KEY_LENGTH_BYTES = 32;

    private final ReserveXProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keyCache = new ConcurrentHashMap<>();

    /**
     * 加密,并返回当前使用的密钥版本 —— 调用方**必须**把 {@code keyId} 一起落库。
     *
     * @return 密文块与其密钥版本;{@code keyId} 写入 {@code user.id_card_key_id}
     */
    public Encrypted encrypt(String plain) {
        String keyId = props.getAes().getKeyId();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);   // 每次随机。IV 不是秘密,只要求不重用

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] block = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, block, 0, iv.length);
            System.arraycopy(ct, 0, block, iv.length, ct.length);
            return new Encrypted(block, keyId);
        } catch (Exception e) {
            throw new IllegalStateException("身份证加密失败(key-id=" + keyId + ")", e);
        }
    }

    /**
     * 解密。{@code keyId} 取自该行的 {@code user.id_card_key_id},**不要传当前 key-id**。
     *
     * <p>调用点必须同时写 {@code audit_log(action='DECRYPT_IDCARD')} —— 明文在系统里
     * 只应出现在注册请求体与此处返回值两个地方。
     */
    public String decrypt(byte[] block, String keyId) {
        if (block == null || block.length <= IV_LENGTH) {
            throw new IllegalArgumentException(
                    "密文块长度 " + (block == null ? "null" : block.length)
                            + " 不合法:至少要有 12B IV + 16B tag");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(block, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[block.length - IV_LENGTH];
            System.arraycopy(block, IV_LENGTH, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 认证失败与"用错了密钥版本"是同一个异常,故把 keyId 带进消息里,否则无从下手
            throw new IllegalStateException(
                    "身份证解密失败(key-id=" + keyId + ")。若该行是轮换前写入的,"
                            + "检查 reservex.aes.keys 是否仍保留了这个版本(03 §2.1)", e);
        }
    }

    /**
     * 脱敏串。**注册时从明文算好落库**,不由密文派生 —— 列表页脱敏展示是高频读,
     * 每行解密一次意味着每行一次 AES 且明文进内存,既慢又扩大明文暴露面。
     * 冗余存脱敏串 = 让绝大多数读路径根本不接触密钥。
     */
    public String mask(String plain) {
        if (plain == null || plain.length() < 8) {
            return "****";
        }
        int keepHead = 3;
        int keepTail = 4;
        return plain.substring(0, keepHead)
                + "*".repeat(plain.length() - keepHead - keepTail)
                + plain.substring(plain.length() - keepTail);
    }

    private SecretKey key(String keyId) {
        return keyCache.computeIfAbsent(keyId, id -> {
            String material = props.getAes().getKeys().get(id);
            if (material == null) {
                throw new IllegalStateException(
                        "未知的 AES key-id=" + id + "。它不在 reservex.aes.keys 里 —— "
                                + "要么是轮换时把旧 key 删了(存量密文将永久不可解),"
                                + "要么是在解一行本不该解密的数据(如超管的 reserved 占位)");
            }
            byte[] raw = Base64.getDecoder().decode(material);
            if (raw.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "AES key-id=" + id + " 解码后 " + raw.length + " 字节,AES-256 要求 "
                                + KEY_LENGTH_BYTES + "。生成:openssl rand -base64 32");
            }
            return new SecretKeySpec(raw, "AES");
        });
    }

    /** 加密结果:密文块 + 密钥版本。两者必须一起落库,否则轮换后解不开。 */
    public record Encrypted(byte[] ciphertext, String keyId) {
    }
}
