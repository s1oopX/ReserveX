package com.reservex.service;

import com.reservex.config.ReserveXProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D4 验证码风控的纯逻辑测试(mock Redis)。
 *
 * <p>核验三个不变量:
 * <ol>
 *   <li><b>一次性</b>:校验后无论对错都删 key —— 防爆破枚举,防重放;</li>
 *   <li><b>风控阈值</b>:失败计数达 captcha-threshold 才置 captcha-required,
 *       未达不置(避免正常用户连点几次售罄就被要求验证码);</li>
 *   <li><b>成功清零</b>:抢号成功后清风控标记与计数器,用户不再被要求验证码。</li>
 * </ol>
 *
 * <p>不测 generate() 的图片生成(easy-captcha 容器内字体渲染属集成测试范畴)。
 */
class CaptchaServiceTest {

    private static final long USER_ID = 42L;
    private static final int THRESHOLD = 5;

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        ReserveXProperties props = new ReserveXProperties();
        props.getRisk().setCaptchaThreshold(THRESHOLD);
        service = new CaptchaService(redis, props);
    }

    // ---- 一次性校验 ----

    @Test
    void verifyDeletesKeyOnSuccess() {
        String key = "abc";
        when(valueOps.getAndDelete("captcha:" + key)).thenReturn("ABCD");
        // 忽略大小写 + trim
        assertThat(service.verify(key, " abcd ")).isTrue();
        // 校验后必删,一次性语义
        verify(valueOps).getAndDelete("captcha:" + key);
    }

    @Test
    void verifyDeletesKeyOnFailureToo() {
        String key = "abc";
        when(valueOps.getAndDelete("captcha:" + key)).thenReturn("ABCD");
        // 输入错误也删 —— 防爆破枚举:攻击者不能反复试同一码
        assertThat(service.verify(key, "WRONG")).isFalse();
        verify(valueOps).getAndDelete("captcha:" + key);
    }

    @Test
    void verifyDeletesKeyEvenIfMissing() {
        String key = "abc";
        when(valueOps.getAndDelete("captcha:" + key)).thenReturn(null);
        // key 不存在(已过期或已用过)也走删,且返 false
        assertThat(service.verify(key, "ABCD")).isFalse();
        verify(valueOps).getAndDelete("captcha:" + key);
    }

    @Test
    void verifyRejectsBlankInputs() {
        assertThat(service.verify(null, "ABCD")).isFalse();
        assertThat(service.verify("  ", "ABCD")).isFalse();
        assertThat(service.verify("abc", null)).isFalse();
        assertThat(service.verify("abc", "  ")).isFalse();
        // 空 key 不应触发删除(没有 key 可删)
        verify(valueOps, never()).getAndDelete(any(String.class));
    }

    // ---- 风控阈值 ----

    @Test
    void captchaRequiredOnlyAfterThresholdFailures() {
        long uid = USER_ID;
        String counterKey = "risk:user:" + uid;
        String requiredKey = "captcha-required:user:" + uid;

        // 阈值=5。前 4 次失败只 INCR 计数器,不置 required。
        for (long i = 1; i < THRESHOLD; i++) {
            when(valueOps.increment(counterKey)).thenReturn(i);
            service.recordGrabFailure(uid);
            // 首次失败设置计数器过期时间
            if (i == 1) {
                verify(valueOps).getAndExpire(eq(counterKey), any(Duration.class));
            }
        }
        // 前 4 次都不应置 captcha-required(set 在 opsForValue 上,不在 redis 直接调用)
        verify(valueOps, never()).set(eq(requiredKey), eq("1"), any(Duration.class));

        // 第 5 次达阈值 → 置 required
        when(valueOps.increment(counterKey)).thenReturn((long) THRESHOLD);
        service.recordGrabFailure(uid);
        verify(valueOps, times(1)).set(eq(requiredKey), eq("1"), any(Duration.class));
    }

    @Test
    void isCaptchaRequiredReflectsRedisPresence() {
        when(redis.hasKey("captcha-required:user:" + USER_ID)).thenReturn(true);
        assertThat(service.isCaptchaRequired(USER_ID)).isTrue();

        when(redis.hasKey("captcha-required:user:" + USER_ID)).thenReturn(false);
        assertThat(service.isCaptchaRequired(USER_ID)).isFalse();
    }

    // ---- 成功清零 ----

    @Test
    void clearRiskOnSuccessDeletesBothKeys() {
        service.clearRiskOnSuccess(USER_ID);
        // 成功后清风控标记 + 计数器
        verify(redis).delete("captcha-required:user:" + USER_ID);
        verify(redis).delete("risk:user:" + USER_ID);
    }
}
