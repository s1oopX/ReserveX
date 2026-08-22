package com.reservex.service;

import cn.hutool.crypto.digest.BCrypt;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.entity.AuditLog;
import com.reservex.entity.EmailRoute;
import com.reservex.entity.IdCardIdentity;
import com.reservex.entity.PhoneRoute;
import com.reservex.entity.RegistrationOutbox;
import com.reservex.entity.User;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.IdCardIdentityMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import com.reservex.mapper.single.RegistrationOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Owns the durable cross-database registration protocol. */
@Slf4j
@Service
public class RegistrationOutboxService {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final EmailRouteMapper emailRoutes;
    private final PhoneRouteMapper phoneRoutes;
    private final IdCardIdentityMapper idCardIdentities;
    private final RegistrationOutboxMapper outboxes;
    private final UserMapper users;
    private final AuditLogMapper audits;
    private final IdGenerator ids;
    private final TimeSupport time;
    private final TransactionTemplate singleTx;

    public RegistrationOutboxService(EmailRouteMapper emailRoutes,
                                     PhoneRouteMapper phoneRoutes,
                                     IdCardIdentityMapper idCardIdentities,
                                     RegistrationOutboxMapper outboxes,
                                     UserMapper users,
                                     AuditLogMapper audits,
                                     IdGenerator ids,
                                     TimeSupport time,
                                     @Qualifier("singleTxManager") PlatformTransactionManager tx) {
        this.emailRoutes = emailRoutes;
        this.phoneRoutes = phoneRoutes;
        this.idCardIdentities = idCardIdentities;
        this.outboxes = outboxes;
        this.users = users;
        this.audits = audits;
        this.ids = ids;
        this.time = time;
        this.singleTx = new TransactionTemplate(tx);
    }

    /** Atomically claims the three routes and durable user payload, then drains it once. */
    public StartOutcome start(User user, long operatorId) {
        return start(user, operatorId, null, null);
    }

    public StartOutcome start(User user, long operatorId, String registrationKey, String requestDigest) {
        if (registrationKey != null && !registrationKey.isBlank()
                && (requestDigest == null || requestDigest.isBlank())) {
            throw new IllegalArgumentException("幂等注册必须提供请求摘要");
        }
        if (registrationKey != null && !registrationKey.isBlank()) {
            RegistrationOutbox existing = outboxes.selectByRegistrationKey(registrationKey);
            if (existing != null) {
                if (!sameRequestIdentity(existing, user, requestDigest)) {
                    throw BizException.of(ErrorCode.REGISTRATION_CONFLICT);
                }
                return new StartOutcome(existing.getUserId(), ensureUser(existing.getUserId()));
            }
        }
        LocalDateTime now = time.now();
        RegistrationOutbox outbox = payload(user, "STAFF".equals(user.getRole()) ? operatorId : null,
                "STAFF".equals(user.getRole()) ? ids.nextId() : null, now, registrationKey,
                requestDigest == null ? null : BCrypt.hashpw(requestDigest));
        try {
            singleTx.executeWithoutResult(status -> {
                if (emailRoutes.insertIgnore(user.getEmail(), user.getUserId(), now) != 1
                        || phoneRoutes.insertIgnore(user.getPhone(), user.getUserId(), now) != 1
                        || idCardIdentities.insertIgnore(user.getIdCardHash(), user.getUserId(), now) != 1) {
                    throw BizException.of(ErrorCode.REGISTRATION_CONFLICT);
                }
                if (outboxes.insert(outbox) != 1) {
                    throw new IllegalStateException("注册 outbox 写入失败 userId=" + user.getUserId());
                }
            });
        } catch (DuplicateKeyException | BizException e) {
            RegistrationOutbox raced = registrationKey == null ? null
                    : outboxes.selectByRegistrationKey(registrationKey);
            if (raced == null || !sameRequestIdentity(raced, user, requestDigest)) {
                throw e;
            }
            return new StartOutcome(raced.getUserId(), ensureUser(raced.getUserId()));
        }
        // The durable intent is the accepted result. A transient shard failure is
        // recovered by RegistrationOutboxWorker and must not turn into a false 5xx.
        return new StartOutcome(user.getUserId(), ensureUser(user.getUserId()));
    }

    public int status(long userId) {
        RegistrationOutbox outbox = outboxes.selectById(userId);
        return outbox == null ? 3 : (outbox.getStatus() == null ? 0 : outbox.getStatus());
    }

    public JobView job(long userId) {
        RegistrationOutbox outbox = outboxes.selectById(userId);
        if (outbox == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return jobView(outbox);
    }

    public JobView retryStuck(long userId, long operatorId) {
        LocalDateTime now = time.now();
        boolean retried = Boolean.TRUE.equals(singleTx.execute(status -> {
            if (outboxes.retryStuck(userId, now) != 1) {
                return false;
            }
            if (audits.insert(registrationRetryAudit(userId, operatorId, now)) != 1) {
                throw new IllegalStateException("写注册任务重试审计失败 userId=" + userId);
            }
            return true;
        }));
        if (retried) {
            return job(userId);
        }
        RegistrationOutbox current = outboxes.selectById(userId);
        if (current == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (Integer.valueOf(3).equals(current.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "注册任务已完成");
        }
        if (current.getStatus() == null || current.getStatus() == 0 || current.getStatus() == 1) {
            return jobView(current);
        }
        throw new BizException(ErrorCode.STATE_CONFLICT, "注册任务状态已变化，请重试");
    }

    public RegistrationOutbox findByRegistrationKey(String registrationKey) {
        if (registrationKey == null || registrationKey.isBlank()) {
            return null;
        }
        return outboxes.selectByRegistrationKey(registrationKey);
    }

    public int statusByRegistrationKey(String registrationKey) {
        RegistrationOutbox outbox = findByRegistrationKey(registrationKey);
        return outbox == null ? -1 : (outbox.getStatus() == null ? 0 : outbox.getStatus());
    }

    /** Shared by the HTTP path and the scheduled worker. */
    public boolean ensureUser(long userId) {
        LocalDateTime now = time.now();
        String owner = UUID.randomUUID().toString();
        if (outboxes.claim(userId, now, now, now.plus(LEASE), owner) != 1) {
            RegistrationOutbox existing = outboxes.selectById(userId);
            return existing == null || Integer.valueOf(3).equals(existing.getStatus());
        }
        RegistrationOutbox outbox = outboxes.selectById(userId);
        if (outbox == null) {
            return true;
        }
        try {
            User expected = user(outbox);
            requireRoutesOwned(expected);
            User existing = users.selectById(userId);
            if (existing == null) {
                existing = insertAndResolveUnknown(expected);
            }
            if (!sameImmutableIdentity(existing, expected)) {
                throw new PermanentFailure("userId 已被另一身份占用 userId=" + userId);
            }
            complete(outbox, owner, now);
            return true;
        } catch (PermanentFailure e) {
            markStuck(userId, owner, e.getMessage(), now);
            return false;
        } catch (RuntimeException e) {
            releaseOrStuck(outbox, owner, e, now);
            return false;
        }
    }

    private User insertAndResolveUnknown(User expected) {
        RuntimeException failure = null;
        try {
            if (users.insert(expected) == 1) {
                return expected;
            }
            failure = new IllegalStateException("用户插入返回 0 行 userId=" + expected.getUserId());
        } catch (RuntimeException e) {
            failure = e;
        }
        User existing = users.selectById(expected.getUserId());
        if (existing == null) {
            throw failure;
        }
        if (!sameImmutableIdentity(existing, expected)) {
            throw new PermanentFailure("userId 已被另一身份占用 userId=" + expected.getUserId());
        }
        log.warn("用户写入结果未知但回读身份一致，按成功收敛 userId={}", expected.getUserId());
        return existing;
    }

    private void complete(RegistrationOutbox outbox, String owner, LocalDateTime now) {
        singleTx.executeWithoutResult(status -> {
            if ("STAFF".equals(outbox.getRole()) && outbox.getAuditId() != null) {
                audits.insertIgnore(staffAudit(outbox));
            }
            int finalized = outbox.getRegistrationKey() == null
                    ? outboxes.deleteCompletedWithoutKey(outbox.getUserId(), owner)
                    : outboxes.complete(outbox.getUserId(), owner, now);
            if (finalized != 1) {
                throw new IllegalStateException("注册 outbox 收口失败 userId=" + outbox.getUserId());
            }
        });
    }

    private void releaseOrStuck(RegistrationOutbox outbox, String owner,
                                RuntimeException failure, LocalDateTime now) {
        String message = safeMessage(failure);
        if (outbox.getAttempts() != null && outbox.getAttempts() >= MAX_ATTEMPTS) {
            markStuck(outbox.getUserId(), owner, message, now);
            return;
        }
        long delaySeconds = Math.min(300L, 1L << Math.min(8, outbox.getAttempts() == null ? 0 : outbox.getAttempts()));
        try {
            outboxes.retry(outbox.getUserId(), owner, now.plusSeconds(delaySeconds), message, now);
        } catch (RuntimeException retryFailure) {
            log.error("注册失败且无法释放租约 userId={}", outbox.getUserId(), retryFailure);
        }
    }

    private void markStuck(long userId, String owner, String error, LocalDateTime now) {
        outboxes.markStuck(userId, owner, error, now);
        log.error("注册转 STUCK userId={} reason={}", userId, error);
    }

    private void requireRoutesOwned(User expected) {
        EmailRoute email = emailRoutes.selectById(expected.getEmail());
        PhoneRoute phone = phoneRoutes.selectById(expected.getPhone());
        IdCardIdentity idCard = idCardIdentities.selectById(expected.getIdCardHash());
        if (email == null || !Objects.equals(email.getUserId(), expected.getUserId())
                || phone == null || !Objects.equals(phone.getUserId(), expected.getUserId())
                || idCard == null || !Objects.equals(idCard.getUserId(), expected.getUserId())) {
            throw new PermanentFailure("注册 route 缺失或归属错误 userId=" + expected.getUserId());
        }
    }

    private static boolean sameImmutableIdentity(User a, User b) {
        return a != null
                && Objects.equals(a.getUserId(), b.getUserId())
                && Objects.equals(a.getEmail(), b.getEmail())
                && Objects.equals(a.getPhone(), b.getPhone())
                && Objects.equals(a.getIdCardHash(), b.getIdCardHash())
                && Objects.equals(a.getRole(), b.getRole())
                && sameTime(a.getCreateAt(), b.getCreateAt());
    }

    private static boolean sameTime(LocalDateTime a, LocalDateTime b) {
        return a != null && b != null
                && a.truncatedTo(ChronoUnit.SECONDS).equals(b.truncatedTo(ChronoUnit.SECONDS));
    }

    private static RegistrationOutbox payload(User user, Long operatorId, Long auditId,
                                              LocalDateTime now, String registrationKey,
                                              String requestFingerprint) {
        RegistrationOutbox outbox = new RegistrationOutbox();
        outbox.setUserId(user.getUserId());
        outbox.setRegistrationKey(registrationKey);
        outbox.setRequestFingerprint(requestFingerprint);
        outbox.setEmail(user.getEmail());
        outbox.setPhone(user.getPhone());
        outbox.setPassword(user.getPassword());
        outbox.setIdCardCiphertext(user.getIdCardCiphertext());
        outbox.setIdCardKeyId(user.getIdCardKeyId());
        outbox.setIdCardHash(user.getIdCardHash());
        outbox.setIdCardMasked(user.getIdCardMasked());
        outbox.setRole(user.getRole());
        outbox.setUserStatus(user.getStatus());
        outbox.setUserVersion(user.getVersion());
        outbox.setUserMustChangePassword(user.getMustChangePassword());
        outbox.setStatus(0);
        outbox.setAttempts(0);
        outbox.setNextAttemptAt(now);
        outbox.setOperatorId(operatorId);
        outbox.setAuditId(auditId);
        outbox.setCreateAt(now);
        outbox.setUpdateAt(now);
        return outbox;
    }

    private static boolean sameRequestIdentity(RegistrationOutbox existing, User user,
                                               String requestDigest) {
        if (existing.getRequestFingerprint() != null) {
            return requestDigest != null
                    && BCrypt.checkpw(requestDigest, existing.getRequestFingerprint());
        }
        return Objects.equals(existing.getEmail(), user.getEmail())
                && Objects.equals(existing.getPhone(), user.getPhone())
                && Objects.equals(existing.getIdCardHash(), user.getIdCardHash())
                && Objects.equals(existing.getRole(), user.getRole());
    }

    private static User user(RegistrationOutbox outbox) {
        User user = new User();
        user.setUserId(outbox.getUserId());
        user.setEmail(outbox.getEmail());
        user.setPhone(outbox.getPhone());
        user.setPassword(outbox.getPassword());
        user.setIdCardCiphertext(outbox.getIdCardCiphertext());
        user.setIdCardKeyId(outbox.getIdCardKeyId());
        user.setIdCardHash(outbox.getIdCardHash());
        user.setIdCardMasked(outbox.getIdCardMasked());
        user.setRole(outbox.getRole());
        user.setStatus(outbox.getUserStatus());
        user.setVersion(outbox.getUserVersion());
        user.setMustChangePassword(outbox.getUserMustChangePassword());
        user.setCreateAt(outbox.getCreateAt());
        user.setUpdateAt(outbox.getUpdateAt());
        return user;
    }

    private static AuditLog staffAudit(RegistrationOutbox outbox) {
        AuditLog audit = new AuditLog();
        audit.setId(outbox.getAuditId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(outbox.getOperatorId());
        audit.setAction("CREATE_STAFF");
        audit.setTargetType("USER");
        audit.setTargetId(outbox.getUserId());
        audit.setAfter("{\"role\":\"STAFF\"}");
        audit.setRequestId("registration-" + outbox.getUserId());
        audit.setCreateAt(outbox.getCreateAt());
        return audit;
    }

    private AuditLog registrationRetryAudit(long userId, long operatorId, LocalDateTime now) {
        AuditLog audit = new AuditLog();
        audit.setId(ids.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(operatorId);
        audit.setAction("RETRY_REGISTRATION");
        audit.setTargetType("REGISTRATION_JOB");
        audit.setTargetId(userId);
        audit.setBefore("{\"status\":\"STUCK\"}");
        audit.setAfter("{\"status\":\"PENDING\"}");
        audit.setRequestId("registration-retry-" + userId);
        audit.setCreateAt(now);
        return audit;
    }

    private static String safeMessage(RuntimeException failure) {
        String text = failure.getMessage();
        if (text == null || text.isBlank()) {
            text = failure.getClass().getSimpleName();
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static JobView jobView(RegistrationOutbox outbox) {
        String status = switch (outbox.getStatus() == null ? 0 : outbox.getStatus()) {
            case 0, 1 -> "PENDING";
            case 2 -> "STUCK";
            case 3 -> "READY";
            default -> throw new IllegalStateException("未知注册任务状态: " + outbox.getStatus());
        };
        return new JobView(outbox.getUserId(), status, outbox.getAttempts(),
                outbox.getLastError(), outbox.getCreateAt(), outbox.getUpdateAt());
    }

    public record JobView(Long userId, String status, Integer attempts, String lastError,
                          LocalDateTime createAt, LocalDateTime updateAt) {
    }

    public record StartOutcome(long userId, boolean ready) {
    }

    private static final class PermanentFailure extends RuntimeException {
        private PermanentFailure(String message) {
            super(message);
        }
    }
}
