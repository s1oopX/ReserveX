package com.reservex.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.util.IdUtil;
import com.reservex.config.ReserveXProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Base64;

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

    private final StringRedisTemplate redis;
    private final ReserveXProperties props;

    public CaptchaService(StringRedisTemplate redis, ReserveXProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * 生成验证码。Redis {@code SET captcha:{uuid} {code} EX 300} → 返回 base64 图片 + key。
     */
    public CaptchaView generate() {
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
     * 校验验证码。校验通过立即删 key(一次性)。校验失败也删,防爆破枚举。
     */
    public boolean verify(String key, String input) {
        if (key == null || key.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String stored = redis.opsForValue().get(captchaKey(key));
        // 校验后无论对错都删,一次性语义
        redis.delete(captchaKey(key));
        if (stored == null) {
            return false;
        }
        return stored.equalsIgnoreCase(input.trim());
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
        Long cnt = redis.opsForValue().increment(counterKey);
        if (cnt != null && cnt == 1L) {
            redis.opsForValue().getAndExpire(counterKey,
                    Duration.ofSeconds(props.getRisk().getRiskCounterTtlSec()));
        }
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
