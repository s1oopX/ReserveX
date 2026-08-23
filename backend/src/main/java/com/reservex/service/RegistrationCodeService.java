package com.reservex.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.config.ReserveXProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class RegistrationCodeService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration REGISTRATION_RECEIPT_TTL = Duration.ofDays(1);
    private static final Duration SEND_LEASE = Duration.ofMinutes(1);
    private static final String CODE_PREFIX = "register-code:";
    private static final String SEND_LOCK_PREFIX = "lock:register-code-send:";
    private static final String RATE_PREFIX = "ratelimit:register-code:";
    private static final String VERIFY_RATE_PREFIX = "ratelimit:register-code-verify:";
    private static final String FAILURE_PREFIX = "register-code-failures:";
    private static final String RECEIPT_PREFIX = "register-code-receipt:";
    private static final int MAX_CODE_FAILURES = 5;
    private static final DefaultRedisScript<Long> CHECK_RATE = new DefaultRedisScript<>("""
            local allowed = 1
            for i, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('EXPIRE', key, ARGV[1]) end
                if count > tonumber(ARGV[i + 1]) then allowed = 0 end
            end
            return allowed
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME_CODE = new DefaultRedisScript<>("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return 0 end
            local failures = tonumber(redis.call('GET', KEYS[2]) or '0')
            if failures >= tonumber(ARGV[2]) then return -2 end
            if stored ~= ARGV[1] then
                failures = redis.call('INCR', KEYS[2])
                if failures == 1 then
                    local ttl = redis.call('PTTL', KEYS[1])
                    if ttl > 0 then redis.call('PEXPIRE', KEYS[2], ttl) end
                end
                if failures >= tonumber(ARGV[2]) then
                    redis.call('DEL', KEYS[1])
                    return -2
                end
                return -1
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CONSUME_FOR_REGISTRATION =
            new DefaultRedisScript<>("""
                    local receipt = redis.call('GET', KEYS[3])
                    if receipt == ARGV[3] then return 2 end
                    local attempts = redis.call('INCR', KEYS[4])
                    if attempts == 1 then redis.call('EXPIRE', KEYS[4], ARGV[5]) end
                    if attempts > tonumber(ARGV[6]) then return -4 end
                    if receipt then return -3 end
                    local stored = redis.call('GET', KEYS[1])
                    if not stored then return 0 end
                    local failures = tonumber(redis.call('GET', KEYS[2]) or '0')
                    if failures >= tonumber(ARGV[2]) then return -2 end
                    if stored ~= ARGV[1] then
                        failures = redis.call('INCR', KEYS[2])
                        if failures == 1 then
                            local ttl = redis.call('PTTL', KEYS[1])
                            if ttl > 0 then redis.call('PEXPIRE', KEYS[2], ttl) end
                        end
                        if failures >= tonumber(ARGV[2]) then
                            redis.call('DEL', KEYS[1])
                            return -2
                        end
                        return -1
                    end
                    redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
                    redis.call('DEL', KEYS[1], KEYS[2])
                    return 1
                    """, Long.class);
    private static final DefaultRedisScript<Long> FINISH_SEND = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if ARGV[3] == '1' and redis.call('GET', KEYS[2]) == ARGV[2] then
                redis.call('DEL', KEYS[2])
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final ReserveXProperties props;
    private final String mailFrom;
    private final SecureRandom random = new SecureRandom();
    private final Semaphore sends = new Semaphore(4);
    private final CircuitBreaker smtpCircuitBreaker;

    public RegistrationCodeService(StringRedisTemplate redis,
                                   JavaMailSender mailSender,
                                   ReserveXProperties props,
                                   @Value("${spring.mail.username}") String mailFrom,
                                   CircuitBreakerRegistry circuitBreakerRegistry) {
        this.redis = redis;
        this.mailSender = mailSender;
        this.props = props;
        this.mailFrom = mailFrom;
        this.smtpCircuitBreaker = circuitBreakerRegistry.circuitBreaker("smtp");
    }

    public void send(String rawEmail, String clientIp) {
        String email = normalize(rawEmail);
        requireRate(email, clientIp);
        if (!sends.tryAcquire()) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        String key = codeKey(email);
        String codeHash = DigestUtil.sha256Hex(code);
        String lockKey = sendLockKey(email);
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        boolean delivered = false;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, lockToken, SEND_LEASE);
            if (acquired == null) {
                throw BizException.of(ErrorCode.SERVICE_DEGRADED);
            }
            if (!acquired) {
                throw BizException.of(ErrorCode.RATE_LIMITED);
            }
            locked = true;
            redis.opsForValue().set(key, codeHash, CODE_TTL);
            redis.delete(failureKey(email));
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("ReserveX 注册验证码");
            message.setText("您的注册验证码是 " + code + "，10 分钟内有效。若非本人操作，请忽略本邮件。");
            smtpCircuitBreaker.executeRunnable(() -> mailSender.send(message));
            delivered = true;
        } catch (BizException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            log.warn("SMTP 熔断中,拒绝发送注册验证码");
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        } catch (RuntimeException e) {
            log.error("注册验证码邮件发送失败", e);
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        } finally {
            if (locked) {
                finishSend(lockKey, key, lockToken, codeHash, !delivered);
            }
            sends.release();
        }
    }

    public void consume(String rawEmail, String code, String clientIp) {
        requireVerifyRate(clientIp);
        String email = normalize(rawEmail);
        String actual = code == null ? "" : DigestUtil.sha256Hex(code.trim());
        Long consumed = redis.execute(CONSUME_CODE,
                List.of(codeKey(email), failureKey(email)), actual,
                Integer.toString(MAX_CODE_FAILURES));
        if (consumed == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (consumed != 1) {
            throw BizException.of(ErrorCode.REGISTRATION_CODE_INVALID);
        }
    }

    /** Atomically consumes the code and leaves a retry receipt before DB registration starts. */
    public boolean consumeForRegistration(String rawEmail, String code, String clientIp,
                                          String registrationKey, String requestFingerprint) {
        if (registrationKey == null || registrationKey.isBlank()
                || requestFingerprint == null || requestFingerprint.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String email = normalize(rawEmail);
        String actual = code == null ? "" : DigestUtil.sha256Hex(code.trim());
        Long consumed = redis.execute(CONSUME_FOR_REGISTRATION,
                List.of(codeKey(email), failureKey(email), receiptKey(registrationKey),
                        verifyRateKey(clientIp)),
                actual, Integer.toString(MAX_CODE_FAILURES), requestFingerprint,
                Long.toString(REGISTRATION_RECEIPT_TTL.toMillis()),
                Integer.toString(props.getRatelimit().getRegisterWindowSec()),
                Integer.toString(props.getRatelimit().getRegisterIpMaxAttempts()));
        if (consumed == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (consumed == -3) {
            throw BizException.of(ErrorCode.REGISTRATION_CONFLICT);
        }
        if (consumed == -4) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
        if (consumed != 1 && consumed != 2) {
            throw BizException.of(ErrorCode.REGISTRATION_CODE_INVALID);
        }
        return consumed == 1;
    }

    private void requireVerifyRate(String clientIp) {
        Long allowed = redis.execute(CHECK_RATE,
                List.of(verifyRateKey(clientIp)),
                Integer.toString(props.getRatelimit().getRegisterWindowSec()),
                Integer.toString(props.getRatelimit().getRegisterIpMaxAttempts()));
        if (allowed == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (allowed != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private void requireRate(String email, String clientIp) {
        Long allowed = redis.execute(CHECK_RATE, List.of(
                        RATE_PREFIX + "email:" + DigestUtil.sha256Hex(email),
                        RATE_PREFIX + "ip:" + DigestUtil.sha256Hex(clientIp)),
                Integer.toString(props.getRatelimit().getRegisterWindowSec()),
                Integer.toString(props.getRatelimit().getRegisterIdentityMaxAttempts()),
                Integer.toString(props.getRatelimit().getRegisterIpMaxAttempts()));
        if (allowed == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        if (allowed != 1) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
    }

    private static String codeKey(String email) {
        return CODE_PREFIX + DigestUtil.sha256Hex(email);
    }

    private static String failureKey(String email) {
        return FAILURE_PREFIX + DigestUtil.sha256Hex(email);
    }

    private static String sendLockKey(String email) {
        return SEND_LOCK_PREFIX + DigestUtil.sha256Hex(email);
    }

    private static String receiptKey(String registrationKey) {
        return RECEIPT_PREFIX + DigestUtil.sha256Hex(registrationKey);
    }

    private static String verifyRateKey(String clientIp) {
        return VERIFY_RATE_PREFIX + "ip:" + DigestUtil.sha256Hex(clientIp);
    }

    private void finishSend(String lockKey, String codeKey, String lockToken,
                            String codeHash, boolean deleteCode) {
        try {
            redis.execute(FINISH_SEND, List.of(lockKey, codeKey), lockToken, codeHash,
                    deleteCode ? "1" : "0");
        } catch (RuntimeException e) {
            log.error("注册验证码发送租约收口失败", e);
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
