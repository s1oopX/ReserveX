package com.reservex.bootstrap;

import com.reservex.config.ReserveXProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 四把密钥的启动断言(08 §4.2)。
 *
 * <p><b>为什么必须是断言而不是文档</b>:「四把密钥两两不等」写在红线清单里,
 * 但红线清单里有而代码里没断言的条目,迟早会被违反 —— 而且违反后**功能全绿**:
 * 共用一把 key 的系统跑起来一切正常,只在泄露那天一次性全线失守。
 * 这类缺陷在任何测试里都跑不出来,只能靠启动时拒绝。
 *
 * <p>四者的差异是断言存在的理由,不是洁癖:
 * <table border="1">
 *   <tr><th>密钥</th><th>泄露后果</th><th>可轮换</th></tr>
 *   <tr><td>{@code AES_KEY}</td><td>历史身份证明文可解</td><td>✅ 按 key_id 并存</td></tr>
 *   <tr><td>{@code ID_HASH_PEPPER}</td><td>hash 可彩虹表反推</td><td>❌ 进了 route PK</td></tr>
 *   <tr><td>{@code QR_HMAC_KEY}</td><td>可伪造任意二维码</td><td>✅ 成本为零</td></tr>
 *   <tr><td>{@code JWT_SECRET}</td><td>可伪造任意用户身份(含 ADMIN)</td><td>✅ 但全员掉线</td></tr>
 * </table>
 * 共用一把 = 用最坏的那条约束绑住全部,且一处泄露即全线失守。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecretGuard {

    /** 密钥最短长度(字符)。32 字节随机值的 base64 是 44 字符,故 32 是宽松下界。 */
    private static final int MIN_SECRET_LENGTH = 32;

    private final ReserveXProperties props;

    /** Sa-Token 的 JWT 密钥不在 reservex.* 下,单独读一次(它是第四把)。 */
    @Value("${sa-token.jwt-secret-key:}")
    private String jwtSecret;

    @PostConstruct
    public void assertSecrets() {
        // ⚠️ 顺序重要:必须**先**查 key-id 是否在密钥集里,再查取到的值空不空。
        //    反过来的话,key-id 拼错时 keys.get() 返 null,会先抛"密钥为空,检查 .env" ——
        //    而 .env 明明是对的,排查方向被彻底带偏。
        //    "报错要指向真因"这件事,在 fail-fast 断言里比断言本身更重要。
        if (!props.getAes().getKeys().containsKey(props.getAes().getKeyId())) {
            throw new IllegalStateException(
                    "reservex.aes.key-id=" + props.getAes().getKeyId()
                            + " 不在 reservex.aes.keys " + props.getAes().getKeys().keySet()
                            + " 里。轮换时必须**先加 key 再改 key-id**,顺序反了"
                            + "会让新写入的密文用一个不存在的密钥版本标记,事后永久解不开(03 §2.1)");
        }
        if (!props.getQr().getAcceptedKeyIds().contains(props.getQr().getKeyId())) {
            throw new IllegalStateException(
                    "reservex.qr.key-id=" + props.getQr().getKeyId()
                            + " 不在 accepted-key-ids " + props.getQr().getAcceptedKeyIds()
                            + " 里 —— 服务端会拒绝自己刚签发的二维码(07 §3.4.1)");
        }

        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("reservex.aes.keys." + props.getAes().getKeyId(),
                props.getAes().getKeys().get(props.getAes().getKeyId()));
        secrets.put("reservex.id-hash.pepper", props.getIdHash().getPepper());
        secrets.put("reservex.qr.hmac-key", props.getQr().getHmacKey());
        secrets.put("sa-token.jwt-secret-key", jwtSecret);

        // ① 存在且够长。空值最常见的来源:.env 缺变量 → compose 静默替换成空串
        secrets.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "密钥 " + name + " 为空。检查 .env 是否缺少对应变量 —— docker compose 会把"
                                + "未定义的 ${VAR} **静默替换为空串**,不会报错(08 §4.2)");
            }
            if (value.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "密钥 " + name + " 长度 " + value.length() + " < " + MIN_SECRET_LENGTH
                                + "。生成:openssl rand -base64 48");
            }
        });

        // ② 两两不等。注意比的是**值**,不是变量名 —— .env 里把同一个值粘贴四遍是最常见的走样方式
        String[] names = secrets.keySet().toArray(new String[0]);
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                if (secrets.get(names[i]).equals(secrets.get(names[j]))) {
                    throw new IllegalStateException(
                            "密钥 " + names[i] + " 与 " + names[j] + " 取值相同。四把密钥必须物理分离:"
                                    + "它们的泄露后果、轮换成本、可轮换性三者全不同,共用一把 = "
                                    + "用最坏的那条约束绑住全部,且一处泄露即全线失守(08 §4.2)");
                }
            }
        }

        log.info("密钥断言通过:四把密钥两两不等且长度合规;aes.key-id={},qr.key-id={}",
                props.getAes().getKeyId(), props.getQr().getKeyId());
    }
}
