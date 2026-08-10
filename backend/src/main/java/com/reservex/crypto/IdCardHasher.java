package com.reservex.crypto;

import com.reservex.config.ReserveXProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * {@code id_card_hash} 的唯一生成口(03 §2.1)。
 *
 * <p><b>{@code hash = SHA-256(pepper || 明文)},pepper 全局固定。</b>
 *
 * <p><b>为什么是 pepper 而不是 per-row salt</b> —— 这是本项目唯一"照密码学教科书做反而错"的地方:
 * <ul>
 *   <li>{@code id_card_hash} 必须**跨用户可比对**:{@code id_card_route} 的主键
 *       {@code (id_card_hash, slot_date)} 就是靠它实现"一人一证一天一约"(M6);</li>
 *   <li>用每行随机盐,同一身份证在两个用户下会得到两个不同 hash → 主键拦不住,
 *       全局唯一直接失效,而且**功能测试全绿**(单用户重复预约照样被拦,只有跨账号才穿);</li>
 *   <li>要的是"攻击者不知道 pepper 就算不出 hash",不是"每行不同"。这正是 pepper 的定义。</li>
 * </ul>
 *
 * <p><b>诚实口径</b>:因为不加 per-row 盐,在**拿到 pepper 的攻击者**面前 hash 是可枚举的
 * (身份证空间名义上 10^17,但地区码 + 生日的结构使实际空间小得多)。
 * 所以 pepper 与 AES 密钥同级保护:不入代码、不入 git、只走环境变量。
 * 被追问"SHA-256 身份证不是能撞出来吗"要答"靠 pepper 保密,不靠算法"。
 *
 * <p><b>pepper 不可轮换</b>:它进了 {@code id_card_route} 的主键,换了等于全表 hash 作废,
 * 需要一次全量重算 + 主键重建。这条已在 03 §2.1 认下,不要对外说"支持轮换"。
 */
@Component
@RequiredArgsConstructor
public class IdCardHasher {

    private final ReserveXProperties props;

    /**
     * @return 64 位小写十六进制串,直接落 {@code CHAR(64)}
     */
    public String hash(String idCardPlain) {
        if (idCardPlain == null || idCardPlain.isBlank()) {
            throw new IllegalArgumentException("身份证明文为空,无法计算 id_card_hash");
        }
        String pepper = props.getIdHash().getPepper();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 顺序固定 pepper 在前。换顺序等于换了一套 hash,存量 route 全部失配
            digest.update(pepper.getBytes(StandardCharsets.UTF_8));
            digest.update(idCardPlain.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
