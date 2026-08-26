package com.reservex.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.config.ReserveXProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 图形验证码生成/校验 + 风控触发(D4)。
 *
 * <p><b>最小可用策略</b>:不全量强制验证码(避免每次抢号都刷码),只在风控判定该用户
 * 需要验证时强制 —— 抢号失败累计达 {@code captcha-threshold} 后置 {@code captcha-required}
 * 标记,此后该用户抢号必须带有效 captchaToken。
 *
 * <p>⚠️ <b>验证码不进抢号 Lua</b>:它在 Lua 之前做,有验证码需求时多一次 Redis GET
 * (仅被风控的用户),正常用户无额外 round-trip,不违反 2 round-trip 硬约束。
 *
 * <p>⚠️ <b>验证码一次性</b>:校验后立即删 key,防重放。校验与抢号非原子 ——
 * 验证码通过后用户仍可能因售罄失败,这不影响风控计数的合理性(失败计数仍累加)。
 *
 * <p>用 Hutool 的 {@link LineCaptcha}(easy-captcha 1.6.2 已是项目依赖),
 * 4 位字符、带干扰线。Base64 内联返回,前端无需额外请求图片资源。
 */
@Slf4j
@Service
public class CaptchaService {

    private static final String CAPTCHA_KEY_PREFIX = "captcha:";
    private static final String CAPTCHA_REQUIRED_PREFIX = "captcha-required:user:";
    private static final String RISK_COUNTER_PREFIX = "risk:user:";
    private static final String CAPTCHA_RATE_PREFIX = "ratelimit:captcha:";
    private static final DefaultRedisScript<Long> CHECK_IP_RATE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count <= tonumber(ARGV[2]) and 1 or 0
            """, Long.class);
    /**
     * 风控计数:INCR + 保证有 TTL,原子。
     *
     * <p>拆成两条命令(INCR 后再 EXPIRE)时,两者之间进程崩溃会留下**永不过期**的计数器 ——
     * 而它一旦越过阈值,该用户就被永久判定"需要验证码"(滚动窗口再也不滚)。
     * 用 {@code EXPIRE NX} 而非"仅 count==1 时设":后者修不掉已经漏成无 TTL 的老 key。
     *
     * <p>返回累加后的值,调用方据此判阈值。
     */
    private static final DefaultRedisScript<Long> BUMP_RISK = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1], 'NX')
            return count
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ReserveXProperties props;

    public CaptchaService(StringRedisTemplate redis, ReserveXProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 生成验证码。Redis {@code SET captcha:{uuid} {code} EX 300} → 返回 base64 图片 + key。
     */
    public CaptchaView generate(String clientIp) {
        requireIpRate("generate", clientIp,
                props.getRatelimit().getCaptchaGenerateIpMaxAttempts());
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 30);
        String code = captcha.getCode();
        String key = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(captchaKey(key), code,
                Duration.ofSeconds(props.getRisk().getCaptchaTtlSec()));
        String base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(
                ImgUtil.toBytes(captcha.getImage(), "png"));
        return new CaptchaView(key, base64);
    }

    /**
     * 校验验证码。原子取并删 key(一次性),并发请求只有一个能读到答案。
     */
    public boolean verify(String key, String input) {
        if (key == null || key.isBlank() || key.length() > 64
                || input == null || input.isBlank() || input.length() > 64) {
            return false;
        }
        String stored = redis.opsForValue().getAndDelete(captchaKey(key));
        if (stored == null) {
            return false;
        }
        return stored.equalsIgnoreCase(input.trim());
    }

    public boolean verifyPublic(String key, String input, String clientIp) {
        requireIpRate("verify", clientIp,
                props.getRatelimit().getCaptchaVerifyIpMaxAttempts());
        return verify(key, input);
    }

    private void requireIpRate(String action, String clientIp, int limit) {
        Long allowed = redis.execute(CHECK_IP_RATE,
                List.of(CAPTCHA_RATE_PREFIX + action + ":ip:" + DigestUtil.sha256Hex(clientIp)),
                Integer.toString(props.getRatelimit().getCaptchaIpWindowSec()),
                Integer.toString(limit));
        if (allowed == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (allowed != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    /** 该用户当前是否被风控要求验证码。 */
    public boolean isCaptchaRequired(long userId) {
        return Boolean.TRUE.equals(redis.hasKey(captchaRequiredKey(userId)));
    }

    /**
     * 抢号失败时累加风控计数。达阈值后置 captcha-required 标记。
     * 仅在售罄(SLOT_FULL)/ 配额已用(QUOTA_USED)等"疑似刷"的失败时调。
     */
    public void recordGrabFailure(long userId) {
        String counterKey = riskCounterKey(userId);
        Long cnt = redis.execute(BUMP_RISK, List.of(counterKey),
                Integer.toString(props.getRisk().getRiskCounterTtlSec()));
        int threshold = props.getRisk().getCaptchaThreshold();
        if (cnt != null && cnt >= threshold) {
            redis.opsForValue().set(captchaRequiredKey(userId), "1",
                    Duration.ofSeconds(props.getRisk().getCaptchaRequiredTtlSec()));
            log.info("用户 {} 抢号失败 {} 次达阈值,已置 captcha-required", userId, cnt);
        }
    }

    /** 抢号成功后清除风控标记(用户已通过验证或正常完成预约,不再需要验证码)。 */
    public void clearRiskOnSuccess(long userId) {
        redis.delete(captchaRequiredKey(userId));
        redis.delete(riskCounterKey(userId));
    }

    private String captchaKey(String key) {
        return CAPTCHA_KEY_PREFIX + key;
    }

    private String captchaRequiredKey(long userId) {
        return CAPTCHA_REQUIRED_PREFIX + userId;
    }

    private String riskCounterKey(long userId) {
        return RISK_COUNTER_PREFIX + userId;
    }

    public record CaptchaView(String key, String imageBase64) {
    }
}
