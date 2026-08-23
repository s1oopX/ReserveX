package com.reservex.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.config.ReserveXProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class RegistrationCodeServiceTest {

    @Test
    void sendsAHashedCodeAfterEmailAndIpRateChecks() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(1))))
                .thenReturn(true);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("3"), eq("20"));
        JavaMailSender mail = mock(JavaMailSender.class);
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mail, new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        service.send(" USER@example.com ", "203.0.113.10");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        String code = message.getValue().getText().replaceAll(".*?(\\d{6}).*", "$1");
        String key = "register-code:" + DigestUtil.sha256Hex("user@example.com");
        verify(values).set(key, DigestUtil.sha256Hex(code), Duration.ofMinutes(10));
        verify(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(
                        "ratelimit:register-code:email:" + DigestUtil.sha256Hex("user@example.com"),
                        "ratelimit:register-code:ip:" + DigestUtil.sha256Hex("203.0.113.10"))),
                eq("3600"), eq("3"), eq("20"));
    }

    @Test
    void wrongCodeDoesNotInvalidateTheCorrectCode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        String ip = "203.0.113.10";
        String codeKey = "register-code:" + DigestUtil.sha256Hex("user@example.com");
        String failureKey = "register-code-failures:" + DigestUtil.sha256Hex("user@example.com");
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class),
                eq(List.of("ratelimit:register-code-verify:ip:" + DigestUtil.sha256Hex(ip))),
                eq("3600"), eq("20"));
        doReturn(-1L).when(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(codeKey, failureKey)), eq(DigestUtil.sha256Hex("000000")), eq("5"));
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(codeKey, failureKey)), eq(DigestUtil.sha256Hex("123456")), eq("5"));
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mock(JavaMailSender.class), new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        BizException wrong = assertThrows(BizException.class,
                () -> service.consume("USER@example.com", "000000", ip));
        assertEquals(ErrorCode.REGISTRATION_CODE_INVALID, wrong.getErrorCode());
        assertDoesNotThrow(() -> service.consume("user@example.com", "123456", ip));
        verify(redis, never()).delete(codeKey);
    }

    @Test
    void tooManyEmailScopedFailuresInvalidateTheCodeAcrossIps() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        String codeKey = "register-code:" + DigestUtil.sha256Hex("user@example.com");
        String failureKey = "register-code-failures:" + DigestUtil.sha256Hex("user@example.com");
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.argThat((List<String> keys) ->
                        keys.size() == 1 && keys.get(0).startsWith("ratelimit:register-code-verify:ip:")),
                eq("3600"), eq("20"));
        doReturn(-2L).when(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(codeKey, failureKey)), eq(DigestUtil.sha256Hex("000000")), eq("5"));
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mock(JavaMailSender.class), new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        BizException error = assertThrows(BizException.class,
                () -> service.consume("user@example.com", "000000", "198.51.100.9"));

        assertEquals(ErrorCode.REGISTRATION_CODE_INVALID, error.getErrorCode());
    }

    @Test
    void registrationReceiptClosesTheCodeConsumeRetryWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        String ip = "203.0.113.10";
        String key = "01234567-89ab-cdef-0123-456789abcdef";
        String fingerprint = "request-fingerprint";
        String emailHash = DigestUtil.sha256Hex("user@example.com");
        List<String> receiptKeys = List.of(
                "register-code:" + emailHash,
                "register-code-failures:" + emailHash,
                "register-code-receipt:" + DigestUtil.sha256Hex(key),
                "ratelimit:register-code-verify:ip:" + DigestUtil.sha256Hex(ip));
        doReturn(1L, 2L, -3L).when(redis).execute(any(DefaultRedisScript.class),
                eq(receiptKeys), eq(DigestUtil.sha256Hex("123456")), eq("5"),
                any(String.class), eq(Long.toString(Duration.ofDays(1).toMillis())),
                eq("3600"), eq("20"));
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mock(JavaMailSender.class), new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        assertTrue(service.consumeForRegistration(
                "user@example.com", "123456", ip, key, fingerprint));
        assertFalse(service.consumeForRegistration(
                "user@example.com", "123456", ip, key, fingerprint));
        BizException mismatch = assertThrows(BizException.class,
                () -> service.consumeForRegistration(
                        "user@example.com", "123456", ip, key, "another-fingerprint"));

        assertEquals(ErrorCode.REGISTRATION_CONFLICT, mismatch.getErrorCode());
    }

    @Test
    void failedDeliveryDeletesTheUnusableCode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(1))))
                .thenReturn(true);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("3"), eq("20"));
        JavaMailSender mail = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mail, new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        BizException error = assertThrows(BizException.class,
                () -> service.send("user@example.com", "203.0.113.10"));

        assertEquals(ErrorCode.SERVICE_DEGRADED, error.getErrorCode());
        String emailHash = DigestUtil.sha256Hex("user@example.com");
        verify(redis).execute(any(DefaultRedisScript.class),
                eq(List.of("lock:register-code-send:" + emailHash, "register-code:" + emailHash)),
                any(String.class), any(String.class), eq("1"));
        verify(redis, never()).delete("register-code:" + emailHash);
    }

    @Test
    void repeatedSmtpFailuresOpenTheCircuit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(1))))
                .thenReturn(true);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("3"), eq("20"));
        JavaMailSender mail = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("smtp down")).when(mail).send(any(SimpleMailMessage.class));
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .build());
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mail, new ReserveXProperties(), "reservex@qq.com", registry);

        for (int i = 0; i < 3; i++) {
            BizException error = assertThrows(BizException.class,
                    () -> service.send("user@example.com", "203.0.113.10"));
            assertEquals(ErrorCode.SERVICE_DEGRADED, error.getErrorCode());
        }

        verify(mail, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void concurrentSendForTheSameEmailIsRejectedBeforeOverwritingTheCode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("3"), eq("20"));
        when(values.setIfAbsent(any(String.class), any(String.class), eq(Duration.ofMinutes(1))))
                .thenReturn(false);
        JavaMailSender mail = mock(JavaMailSender.class);
        RegistrationCodeService service = new RegistrationCodeService(
                redis, mail, new ReserveXProperties(), "reservex@qq.com", CircuitBreakerRegistry.ofDefaults());

        BizException error = assertThrows(BizException.class,
                () -> service.send("user@example.com", "203.0.113.10"));

        assertEquals(ErrorCode.RATE_LIMITED, error.getErrorCode());
        verify(values, never()).set(any(String.class), any(String.class), any(Duration.class));
        verify(mail, never()).send(any(SimpleMailMessage.class));
    }
}
