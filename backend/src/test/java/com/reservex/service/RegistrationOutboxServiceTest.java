package com.reservex.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationOutboxServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 10, 0);

    @Test
    void replayAfterCrashInsertsUserAndDeletesUnkeyedOutbox() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("USER", 11L);
        when(f.outboxes.claim(eq(11L), any(), any(), any(), any())).thenReturn(1);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);
        when(f.emailRoutes.selectById("person@example.com")).thenReturn(email(11L));
        when(f.phoneRoutes.selectById("13800138000")).thenReturn(phone(11L));
        when(f.identities.selectById("hash")).thenReturn(identity(11L));
        when(f.users.selectById(11L)).thenReturn(null);
        when(f.users.insert(any(User.class))).thenReturn(1);

        assertTrue(f.service.ensureUser(11L));
        verify(f.users).insert(any(User.class));
        verify(f.outboxes).deleteCompletedWithoutKey(eq(11L), any());
    }

    @Test
    void duplicateWriteReReadsImmutableIdentityAndDoesNotOverwriteMutableState() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("USER", 11L);
        outbox.setRegistrationKey("01234567-89ab-cdef-0123-456789abcdef");
        outbox.setRequestFingerprint("bcrypt-request-fingerprint");
        User existing = user(11L);
        existing.setStatus(1);
        existing.setPassword("a-different-bcrypt");
        when(f.outboxes.claim(eq(11L), any(), any(), any(), any())).thenReturn(1);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);
        when(f.emailRoutes.selectById("person@example.com")).thenReturn(email(11L));
        when(f.phoneRoutes.selectById("13800138000")).thenReturn(phone(11L));
        when(f.identities.selectById("hash")).thenReturn(identity(11L));
        when(f.users.selectById(11L)).thenReturn(null, existing);
        when(f.users.insert(any(User.class))).thenThrow(new DuplicateKeyException("unknown commit"));

        assertTrue(f.service.ensureUser(11L));
        verify(f.outboxes).complete(eq(11L), any(), any());
    }

    @Test
    void conflictingImmutableIdentityIsStuckWithoutUserOverwrite() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("USER", 11L);
        User existing = user(11L);
        existing.setEmail("someone-else@example.com");
        when(f.outboxes.claim(eq(11L), any(), any(), any(), any())).thenReturn(1);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);
        when(f.emailRoutes.selectById("person@example.com")).thenReturn(email(11L));
        when(f.phoneRoutes.selectById("13800138000")).thenReturn(phone(11L));
        when(f.identities.selectById("hash")).thenReturn(identity(11L));
        when(f.users.selectById(11L)).thenReturn(existing);

        assertFalse(f.service.ensureUser(11L));
        verify(f.outboxes).markStuck(eq(11L), any(), any(), any());
    }

    @Test
    void transientWriteFailureReleasesLeaseForRetry() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("USER", 11L);
        when(f.outboxes.claim(eq(11L), any(), any(), any(), any())).thenReturn(1);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);
        when(f.emailRoutes.selectById("person@example.com")).thenReturn(email(11L));
        when(f.phoneRoutes.selectById("13800138000")).thenReturn(phone(11L));
        when(f.identities.selectById("hash")).thenReturn(identity(11L));
        // 链式而非 thenReturn(null, null):后者的末位 null 会被 javac 当成
        // 可能的 varargs 数组本身,报 non-varargs call 告警。语义完全相同。
        when(f.users.selectById(11L)).thenReturn(null).thenReturn(null);
        when(f.users.insert(any(User.class))).thenThrow(new IllegalStateException("ds down"));

        assertFalse(f.service.ensureUser(11L));
        verify(f.outboxes).retry(eq(11L), any(), any(), any(), any());
    }

    @Test
    void identityClaimConflictRejectsBeforeOutboxAndUserWrites() {
        Fixture f = fixture();
        User user = user(11L);
        user.setPassword("bcrypt");
        user.setIdCardCiphertext(new byte[]{1});
        user.setIdCardKeyId("aes-v1");
        user.setIdCardMasked("1101**********2X");
        when(f.emailRoutes.insertIgnore(user.getEmail(), user.getUserId(), NOW)).thenReturn(1);
        when(f.phoneRoutes.insertIgnore(user.getPhone(), user.getUserId(), NOW)).thenReturn(1);
        when(f.identities.insertIgnore(user.getIdCardHash(), user.getUserId(), NOW)).thenReturn(0);

        BizException error = assertThrows(BizException.class,
                () -> f.service.start(user, 0L));

        assertEquals(ErrorCode.REGISTRATION_CONFLICT, error.getErrorCode());
        verify(f.outboxes, never()).insert(any(RegistrationOutbox.class));
        verify(f.users, never()).insert(any(User.class));
    }

    @Test
    void keyedReplayReturnsThePersistedUserId() {
        Fixture f = fixture();
        User requested = user(22L);
        requested.setPassword("bcrypt");
        requested.setIdCardCiphertext(new byte[]{1});
        requested.setIdCardKeyId("aes-v1");
        requested.setIdCardMasked("1101**********2X");
        RegistrationOutbox existing = outbox("USER", 11L);
        existing.setRegistrationKey("01234567-89ab-cdef-0123-456789abcdef");
        existing.setStatus(3);
        when(f.outboxes.selectByRegistrationKey(existing.getRegistrationKey())).thenReturn(existing);
        when(f.outboxes.claim(eq(11L), any(), any(), any(), any())).thenReturn(0);
        when(f.outboxes.selectById(11L)).thenReturn(existing);

        RegistrationOutboxService.StartOutcome result = f.service.start(requested, 0L,
                existing.getRegistrationKey(), "request-digest");

        assertEquals(11L, result.userId());
        assertTrue(result.ready());
        verify(f.outboxes, never()).insert(any(RegistrationOutbox.class));
    }

    @Test
    void stuckJobCanBeResetForImmediateRetry() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("STAFF", 11L);
        outbox.setStatus(0);
        outbox.setAttempts(0);
        outbox.setLastError(null);
        when(f.outboxes.retryStuck(11L, NOW)).thenReturn(1);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);

        RegistrationOutboxService.JobView retried = f.service.retryStuck(11L, 7L);

        assertEquals("PENDING", retried.status());
        assertEquals(0, retried.attempts());
        verify(f.outboxes).retryStuck(11L, NOW);
        verify(f.audits).insert(org.mockito.ArgumentMatchers.<AuditLog>argThat(
                audit -> audit.getOperatorId() == 7L
                && "RETRY_REGISTRATION".equals(audit.getAction())
                && audit.getTargetId() == 11L));
    }

    @Test
    void completedJobCannotBeMovedBackToPending() {
        Fixture f = fixture();
        RegistrationOutbox outbox = outbox("STAFF", 11L);
        outbox.setStatus(3);
        when(f.outboxes.retryStuck(11L, NOW)).thenReturn(0);
        when(f.outboxes.selectById(11L)).thenReturn(outbox);

        BizException error = assertThrows(BizException.class,
                () -> f.service.retryStuck(11L, 7L));

        assertEquals(ErrorCode.STATE_CONFLICT, error.getErrorCode());
        verify(f.audits, never()).insert(any(AuditLog.class));
    }

    private static Fixture fixture() {
        RegistrationOutboxMapper outboxes = mock(RegistrationOutboxMapper.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());
        doNothing().when(tx).rollback(any());
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(NOW);
        Fixture f = new Fixture();
        f.outboxes = outboxes;
        when(outboxes.complete(any(Long.class), any(), any())).thenReturn(1);
        when(outboxes.deleteCompletedWithoutKey(any(Long.class), any())).thenReturn(1);
        f.emailRoutes = mock(EmailRouteMapper.class);
        f.phoneRoutes = mock(PhoneRouteMapper.class);
        f.identities = mock(IdCardIdentityMapper.class);
        f.users = mock(UserMapper.class);
        f.audits = mock(AuditLogMapper.class);
        when(f.audits.insert(any(AuditLog.class))).thenReturn(1);
        f.service = new RegistrationOutboxService(f.emailRoutes, f.phoneRoutes, f.identities,
                outboxes, f.users, f.audits, mock(IdGenerator.class), time, tx);
        return f;
    }

    private static RegistrationOutbox outbox(String role, long userId) {
        RegistrationOutbox outbox = new RegistrationOutbox();
        outbox.setUserId(userId);
        outbox.setEmail("person@example.com");
        outbox.setPhone("13800138000");
        outbox.setPassword("bcrypt");
        outbox.setIdCardKeyId("aes-v1");
        outbox.setIdCardHash("hash");
        outbox.setIdCardMasked("1101**********2X");
        outbox.setRole(role);
        outbox.setStatus(1);
        outbox.setAttempts(1);
        outbox.setCreateAt(NOW);
        outbox.setUpdateAt(NOW);
        return outbox;
    }

    private static User user(long id) {
        User user = new User();
        user.setUserId(id);
        user.setEmail("person@example.com");
        user.setPhone("13800138000");
        user.setIdCardHash("hash");
        user.setRole("USER");
        user.setCreateAt(NOW);
        return user;
    }

    private static EmailRoute email(long id) {
        EmailRoute route = new EmailRoute();
        route.setUserId(id);
        return route;
    }

    private static PhoneRoute phone(long id) {
        PhoneRoute route = new PhoneRoute();
        route.setUserId(id);
        return route;
    }

    private static IdCardIdentity identity(long id) {
        IdCardIdentity route = new IdCardIdentity();
        route.setUserId(id);
        return route;
    }

    private static final class Fixture {
        private RegistrationOutboxService service;
        private RegistrationOutboxMapper outboxes;
        private EmailRouteMapper emailRoutes;
        private PhoneRouteMapper phoneRoutes;
        private IdCardIdentityMapper identities;
        private UserMapper users;
        private AuditLogMapper audits;
    }
}
