package com.reservex.service;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.session.SaSession;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.crypto.digest.DigestUtil;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.crypto.IdCardCipher;
import com.reservex.crypto.IdCardHasher;
import com.reservex.entity.AuditLog;
import com.reservex.entity.RegistrationOutbox;
import com.reservex.entity.User;
import com.reservex.entity.EmailRoute;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.EmailRouteMapper;
import com.reservex.mapper.single.IdCardIdentityMapper;
import com.reservex.mapper.single.PhoneRouteMapper;
import com.reservex.mapper.single.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class AuthServiceTest {

    @Test
    void staffCreationReplayReturnsThePersistedJobId() {
        RegistrationOutboxService outboxes = mock(RegistrationOutboxService.class);
        when(outboxes.start(any(User.class), eq(7L),
                eq("01234567-89ab-cdef-0123-456789abcdef"), anyString()))
                .thenReturn(new RegistrationOutboxService.StartOutcome(11L, false));
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(42L, 99L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 19, 10, 0));
        IdCardCipher cipher = mock(IdCardCipher.class);
        when(cipher.encrypt("11010519491231002X"))
                .thenReturn(new IdCardCipher.Encrypted(new byte[]{1}, "aes-v1"));
        when(cipher.mask("11010519491231002X")).thenReturn("1101**********2X");
        IdCardHasher hasher = mock(IdCardHasher.class);
        when(hasher.hash("11010519491231002X")).thenReturn("hash");
        AuditLogMapper audits = mock(AuditLogMapper.class);
        when(audits.insert(any(AuditLog.class))).thenReturn(1);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class),
                mock(IdCardIdentityMapper.class), audits, mock(UserMapper.class), ids, time,
                cipher, hasher, mock(StringRedisTemplate.class), mock(StpLogic.class),
                new ReserveXProperties(), tx, outboxes);

        AuthService.RegistrationOutcome outcome = service.createStaff(
                "staff@example.com", "13800138000", "password-1",
                "11010519491231002X", 7L,
                "01234567-89ab-cdef-0123-456789abcdef");

        assertEquals(11L, outcome.userId());
        assertFalse(outcome.ready());
    }

    @Test
    void idempotencyKeyCannotBeReusedForAnotherRegistrationPayload() {
        RegistrationOutboxService outboxes = mock(RegistrationOutboxService.class);
        IdCardHasher hasher = mock(IdCardHasher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RegistrationOutbox existing = new RegistrationOutbox();
        existing.setUserId(42L);
        existing.setRegistrationKey("01234567-89ab-cdef-0123-456789abcdef");
        existing.setRequestFingerprint(BCrypt.hashpw(AuthService.registrationRequestDigest(
                "first@example.com", "13800138000", "first-password",
                "11010519491231002X", "USER")));
        existing.setStatus(3);
        when(outboxes.findByRegistrationKey(existing.getRegistrationKey())).thenReturn(existing);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("20"), eq("20"));

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class),
                mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class),
                mock(UserMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                mock(IdCardCipher.class), hasher, redis,
                mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class), outboxes);

        AuthService.RegistrationOutcome replay = service.registrationByKey(
                existing.getRegistrationKey(), " FIRST@example.com ", "13800138000",
                "first-password", "11010519491231002x", "203.0.113.10");
        assertEquals(42L, replay.userId());
        assertTrue(replay.ready());

        BizException mismatch = assertThrows(BizException.class,
                () -> service.registrationByKey(existing.getRegistrationKey(),
                        "other@example.com", "13800138000", "first-password",
                        "11010519491231002X", "203.0.113.10"));
        assertEquals(ErrorCode.REGISTRATION_CONFLICT, mismatch.getErrorCode());
        verify(redis, times(2)).execute(any(DefaultRedisScript.class),
                eq(List.of(
                        "ratelimit:registration-replay:key:" + DigestUtil.sha256Hex(existing.getRegistrationKey()),
                        "ratelimit:registration-replay:ip:" + DigestUtil.sha256Hex("203.0.113.10"))),
                eq("3600"), eq("20"), eq("20"));
    }

    @Test
    void idempotencyReplayIsRejectedBeforeBcryptWhenRateLimited() {
        RegistrationOutboxService outboxes = mock(RegistrationOutboxService.class);
        RegistrationOutbox existing = new RegistrationOutbox();
        existing.setRegistrationKey("01234567-89ab-cdef-0123-456789abcdef");
        existing.setRequestFingerprint("invalid-bcrypt");
        when(outboxes.findByRegistrationKey(existing.getRegistrationKey())).thenReturn(existing);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(2L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("20"), eq("20"));
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class),
                mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class),
                mock(UserMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                mock(IdCardCipher.class), mock(IdCardHasher.class), redis,
                mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class), outboxes);

        BizException error = assertThrows(BizException.class,
                () -> service.registrationByKey(existing.getRegistrationKey(),
                        "first@example.com", "13800138000", "first-password",
                        "11010519491231002X", "203.0.113.10"));

        assertEquals(ErrorCode.RATE_LIMITED, error.getErrorCode());
    }

    @Test
    void idCardMustHaveARealDateAndValidChecksum() {
        assertDoesNotThrow(() -> AuthService.validateIdCard("11010519491231002X"));
        assertThrows(BizException.class,
                () -> AuthService.validateIdCard("11010519490230002X"));
        assertThrows(BizException.class,
                () -> AuthService.validateIdCard("110105194912310021"));
    }

    @Test
    void passwordOnceTokenCanOnlyBeConsumedOnce() {
        UserMapper users = mock(UserMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        String token = "0123456789abcdef0123456789abcdef";
        when(values.getAndDelete("satoken:password-once:" + token)).thenReturn("42", null);

        User user = new User();
        user.setUserId(42L);
        user.setStatus(0);
        user.setPassword(BCrypt.hashpw("old-password"));
        when(users.selectById(42L)).thenReturn(user);
        when(users.updatePassword(eq(42L), eq(user.getPassword()), anyString(),
                any(LocalDateTime.class))).thenReturn(1);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 12, 0));

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class), users,
                mock(IdGenerator.class), time, mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        assertDoesNotThrow(() -> service.changePassword(null, token,
                "old-password", "new-password"));
        assertThrows(BizException.class, () -> service.changePassword(null, token,
                "old-password", "another-password"));
    }

    @Test
    void accountRateLimitAllowsCorrectPasswordAndBindsRefreshToAccess() throws Exception {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        UserMapper users = mock(UserMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(2L).when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), eq("60"), eq("10"), eq("100"));

        EmailRoute route = new EmailRoute();
        route.setEmail("user@example.com");
        route.setUserId(42L);
        when(emails.selectById("user@example.com")).thenReturn(route);

        User user = new User();
        user.setUserId(42L);
        user.setEmail("user@example.com");
        user.setPassword(BCrypt.hashpw("old-password"));
        user.setRole("USER");
        user.setStatus(0);
        user.setIdCardHash("opaque-hash");
        user.setIdCardMasked("1101**********2X");
        when(users.selectById(42L)).thenReturn(user);

        StpLogic stp = mock(StpLogic.class);
        SaSession session = mock(SaSession.class);
        ArgumentCaptor<SaLoginModel> model = ArgumentCaptor.forClass(SaLoginModel.class);
        when(stp.createLoginSession(eq(42L), model.capture())).thenReturn("access-token");
        when(stp.getTokenSessionByToken("access-token")).thenReturn(session);

        AuthService service = authService(
                emails, mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));

        AuthService.LoginOutcome outcome = service.login(
                "USER@example.com", "old-password", "203.0.113.10");

        assertEquals("access-token", outcome.tokens().accessToken());
        assertTrue(model.getValue().getExtraData() == null
                || model.getValue().getExtraData().isEmpty());
        verify(session).set("idCardHash", "opaque-hash");
        verify(session).set("idCardMasked", "1101**********2X");
        String accessBinding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("access-token".getBytes(StandardCharsets.UTF_8)));
        verify(values).set(argThat((String key) -> key.startsWith("satoken:refresh:42:")),
                eq("2|0|" + accessBinding), eq(Duration.ofDays(7)));
        verify(redis).delete(argThat((String key) ->
                key.matches("ratelimit:login:account:[0-9a-f]{64}")));
    }

    @Test
    void staffWithPersistedFlagGetsOnlyPasswordChangeToken() {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        EmailRoute route = new EmailRoute();
        route.setEmail("staff@example.com");
        route.setUserId(42L);
        when(emails.selectById("staff@example.com")).thenReturn(route);

        User staff = new User();
        staff.setUserId(42L);
        staff.setPassword(BCrypt.hashpw("initial-password"));
        staff.setRole("STAFF");
        staff.setStatus(0);
        staff.setMustChangePassword(1);
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(42L)).thenReturn(staff);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("60"), eq("10"), eq("100"));
        StpLogic stp = mock(StpLogic.class);

        AuthService service = authService(
                emails, mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class), users,
                mock(IdGenerator.class), mock(TimeSupport.class), mock(IdCardCipher.class),
                mock(IdCardHasher.class), redis, stp, new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        AuthService.LoginOutcome outcome = service.login(
                "staff@example.com", "initial-password", "203.0.113.10");

        assertTrue(outcome.tokens() == null);
        assertEquals(32, outcome.onceToken().length());
        verify(values).set(argThat((String key) -> key.startsWith("satoken:password-once:")),
                eq("42"), eq(java.time.Duration.ofMinutes(10)));
        verify(stp, never()).createLoginSession(any(), any(SaLoginModel.class));
    }

    @Test
    void loginRateLimitUsesNormalizedEmailHashBeforeDatabaseLookup() {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(3L).when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), eq("60"), eq("10"), eq("100"));

        AuthService service = authService(
                emails, mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class),
                mock(UserMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                mock(IdCardCipher.class), mock(IdCardHasher.class), redis, mock(StpLogic.class),
                new ReserveXProperties(), mock(PlatformTransactionManager.class));

        BizException error = assertThrows(BizException.class,
                () -> service.login("  USER@example.com  ", "wrong-password", "203.0.113.10"));

        assertEquals(ErrorCode.RATE_LIMITED, error.getErrorCode());
        verify(redis).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.size() == 2
                        && keys.getFirst().matches("ratelimit:login:account:[0-9a-f]{64}")
                        && keys.get(1).matches("ratelimit:login:ip:[0-9a-f]{64}")
                        && keys.stream().noneMatch(key -> key.contains("user@example.com"))),
                eq("60"), eq("10"), eq("100"));
        verify(emails, never()).selectById(anyString());
    }

    @Test
    void accountRateLimitChecksCredentialsBeforeRejecting() {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(2L).when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), eq("60"), eq("10"), eq("100"));

        AuthService service = authService(
                emails, mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class),
                mock(UserMapper.class), mock(IdGenerator.class), mock(TimeSupport.class),
                mock(IdCardCipher.class), mock(IdCardHasher.class), redis, mock(StpLogic.class),
                new ReserveXProperties(), mock(PlatformTransactionManager.class));

        BizException error = assertThrows(BizException.class,
                () -> service.login("user@example.com", "wrong-password", "203.0.113.10"));

        assertEquals(ErrorCode.RATE_LIMITED, error.getErrorCode());
        verify(emails).selectById("user@example.com");
    }

    @Test
    void loginRateLimitKeepsAccountBucketAcrossSourceIps() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("60"), eq("10"), eq("100"));
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), mock(UserMapper.class), mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        assertThrows(BizException.class,
                () -> service.login("user@example.com", "wrong", "203.0.113.10"));
        assertThrows(BizException.class,
                () -> service.login("user@example.com", "wrong", "203.0.113.11"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(2)).execute(any(DefaultRedisScript.class), keys.capture(),
                eq("60"), eq("10"), eq("100"));
        assertEquals(keys.getAllValues().get(0).get(0), keys.getAllValues().get(1).get(0));
        assertFalse(keys.getAllValues().get(0).get(1).equals(keys.getAllValues().get(1).get(1)));
    }

    @Test
    void registerRateLimitRunsBeforeIdGenerationAndCrypto() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(2L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("3600"), eq("3"), eq("3"), eq("20"));
        IdGenerator ids = mock(IdGenerator.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), mock(UserMapper.class), ids,
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        BizException error = assertThrows(BizException.class, () -> service.registerUser(
                "  USER@example.com  ", "13800138000", "password-1",
                "11010519491231002X", "203.0.113.10"));

        assertEquals(ErrorCode.RATE_LIMITED, error.getErrorCode());
        verify(redis).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.size() == 3
                        && keys.getFirst().matches("ratelimit:register:email:[0-9a-f]{64}")
                        && keys.get(1).matches("ratelimit:register:phone:[0-9a-f]{64}")
                        && keys.get(2).matches("ratelimit:register:ip:[0-9a-f]{64}")),
                eq("3600"), eq("3"), eq("3"), eq("20"));
        verify(ids, never()).nextId();
    }

    @Test
    void invalidIdCardDoesNotConsumeRegistrationBuckets() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), mock(UserMapper.class), mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        assertThrows(BizException.class, () -> service.registerUser(
                "user@example.com", "13800138000", "password-1",
                "110105194912310021", "203.0.113.10"));

        verify(redis, never()).execute(any(DefaultRedisScript.class), any(List.class),
                any(Object[].class));
    }

    @Test
    void loginRevokesPairWhenPasswordChangesAfterCredentialCheck() {
        EmailRouteMapper emails = mock(EmailRouteMapper.class);
        EmailRoute route = new EmailRoute();
        route.setEmail("user@example.com");
        route.setUserId(42L);
        when(emails.selectById("user@example.com")).thenReturn(route);

        User before = new User();
        before.setUserId(42L);
        before.setPassword(BCrypt.hashpw("old-password"));
        before.setRole("USER");
        before.setStatus(0);
        User after = new User();
        after.setUserId(42L);
        after.setPassword(BCrypt.hashpw("new-password"));
        after.setRole("USER");
        after.setStatus(0);
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(42L)).thenReturn(before, after);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), any(List.class),
                eq("60"), eq("10"), eq("100"));
        StpLogic stp = mock(StpLogic.class);
        when(stp.createLoginSession(eq(42L), any(SaLoginModel.class))).thenReturn("access-token");
        when(stp.getTokenSessionByToken("access-token")).thenReturn(mock(SaSession.class));

        AuthService service = authService(
                emails, mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class), users,
                mock(IdGenerator.class), mock(TimeSupport.class), mock(IdCardCipher.class),
                mock(IdCardHasher.class), redis, stp, new ReserveXProperties(),
                mock(PlatformTransactionManager.class));

        assertThrows(BizException.class, () -> service.login(
                "user@example.com", "old-password", "203.0.113.10"));
        verify(stp).logoutByTokenValue("access-token");
        verify(redis).delete(argThat((String key) -> key.startsWith("satoken:refresh:42:")));
    }

    @Test
    void refreshRevokesNewPairWhenPasswordGenerationChangesMidFlight() {
        UserMapper users = mock(UserMapper.class);
        User user = new User();
        user.setUserId(42L);
        user.setRole("USER");
        user.setStatus(0);
        when(users.selectById(42L)).thenReturn(user);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String oldJti = "0123456789abcdef0123456789abcdef";
        String oldKey = "satoken:refresh:42:" + oldJti;
        doReturn("N|0").doReturn(0L).when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(Object[].class));

        StpLogic stp = mock(StpLogic.class);
        when(stp.createLoginSession(eq(42L), any(SaLoginModel.class))).thenReturn("new-access");
        when(stp.getTokenSessionByToken("new-access")).thenReturn(mock(SaSession.class));

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), mock(AuditLogMapper.class), users,
                mock(IdGenerator.class), mock(TimeSupport.class), mock(IdCardCipher.class),
                mock(IdCardHasher.class), redis, stp, new ReserveXProperties(),
                mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        assertThrows(BizException.class,
                () -> service.refresh("42." + oldJti, "old-access", "203.0.113.10"));
        verify(stp).logoutByTokenValue("new-access");
        verify(redis).delete(argThat((String key) -> key.startsWith("satoken:refresh:42:")
                && !key.equals(oldKey)));
    }

    @Test
    void reusedRefreshRevokesTheWholeCredentialGeneration() {
        UserMapper users = mock(UserMapper.class);
        User user = new User();
        user.setUserId(42L);
        user.setStatus(0);
        when(users.selectById(42L)).thenReturn(user);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn("~REUSED~").when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(Object[].class));

        StpLogic stp = mock(StpLogic.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        assertThrows(BizException.class, () -> service.refresh(
                "42.0123456789abcdef0123456789abcdef", "old-access", "203.0.113.10"));
        verify(values, never()).increment("satoken:refresh-version:42");
        verify(stp).logout(42L);
    }

    @Test
    void repeatedRefreshReturnsTheCachedPairWithoutIssuingAgain() {
        User user = new User();
        user.setUserId(42L);
        user.setRole("USER");
        user.setStatus(0);
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(42L)).thenReturn(user);

        String binding = "4912660d431709a6abb41d138d405255ff711915406e5ec6bd5dac8eeff09d22";
        String newBinding = "d6f0ca52a627e45317cdad1782ad706793ea47a7697f1ceda002373bc8daa617";
        String newRefresh = "42.fedcba9876543210fedcba9876543210";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn("R|" + binding + "|0|new-access|" + newRefresh + "|" + newBinding)
                .when(redis).execute(any(DefaultRedisScript.class),
                        any(List.class), any(Object[].class));
        StpLogic stp = mock(StpLogic.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        AuthService.TokenPair pair = service.refresh(
                "42.0123456789abcdef0123456789abcdef", "old-access", "203.0.113.10");

        assertEquals("new-access", pair.accessToken());
        assertEquals(newRefresh, pair.refreshToken());
        verify(stp, never()).createLoginSession(any(), any(SaLoginModel.class));
    }

    @Test
    void logoutRevokesAPairThatFinishedRotatingConcurrently() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn("new-access").when(redis).execute(any(DefaultRedisScript.class),
                eq(List.of(
                        "satoken:refresh:42:0123456789abcdef0123456789abcdef",
                        "satoken:refresh-used:42:0123456789abcdef0123456789abcdef",
                        "satoken:refresh-receipt:42:0123456789abcdef0123456789abcdef")),
                eq("42"));
        StpLogic stp = mock(StpLogic.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), mock(UserMapper.class), mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));

        service.logout(null, "42.0123456789abcdef0123456789abcdef");

        verify(stp).logoutByTokenValue("new-access");
        verify(stp, never()).logout(42L);
    }

    @Test
    void differentBindingDuringPendingDoesNotRevokeTheWinner() {
        User user = new User();
        user.setUserId(42L);
        user.setStatus(0);
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(42L)).thenReturn(user);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn("~MISMATCH~").when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(Object[].class));
        StpLogic stp = mock(StpLogic.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        BizException error = assertThrows(BizException.class, () -> service.refresh(
                "42.0123456789abcdef0123456789abcdef", "different-access", "203.0.113.10"));

        assertEquals(ErrorCode.UNAUTHORIZED, error.getErrorCode());
        verify(stp, never()).logout(42L);
    }

    @Test
    void refreshCannotBypassPersistedPasswordChangeRequirement() {
        UserMapper users = mock(UserMapper.class);
        User staff = new User();
        staff.setUserId(42L);
        staff.setStatus(0);
        staff.setMustChangePassword(1);
        when(users.selectById(42L)).thenReturn(staff);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, mock(StpLogic.class), new ReserveXProperties(),
                mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        assertThrows(BizException.class, () -> service.refresh(
                "42.0123456789abcdef0123456789abcdef", "old-access", "203.0.113.10"));
        verify(redis).delete("satoken:refresh:42:0123456789abcdef0123456789abcdef");
        verify(redis, never()).execute(any(DefaultRedisScript.class), any(List.class), anyString());
    }

    @Test
    void staleRefreshMarkerDoesNotRevokeTheCurrentCredentialGeneration() {
        UserMapper users = mock(UserMapper.class);
        User user = new User();
        user.setUserId(42L);
        user.setStatus(0);
        when(users.selectById(42L)).thenReturn(user);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doReturn("~STALE~").when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(Object[].class));

        StpLogic stp = mock(StpLogic.class);
        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class),
                mock(AuditLogMapper.class), users, mock(IdGenerator.class),
                mock(TimeSupport.class), mock(IdCardCipher.class), mock(IdCardHasher.class),
                redis, stp, new ReserveXProperties(), mock(PlatformTransactionManager.class));
        allowRefreshRate(redis);

        assertThrows(BizException.class, () -> service.refresh(
                "42.0123456789abcdef0123456789abcdef", "old-access", "203.0.113.10"));
        verify(values, never()).increment("satoken:refresh-version:42");
        verify(stp, never()).logout(42L);
    }

    @Test
    void banningStaffUpdatesStatusThenRevokesAllCredentialsAndClosesAudit() {
        UserMapper users = mock(UserMapper.class);
        User staff = new User();
        staff.setUserId(42L);
        staff.setRole("STAFF");
        staff.setStatus(0);
        staff.setVersion(3);
        staff.setEmail("staff@example.com");
        staff.setPhone("13800138000");
        staff.setIdCardMasked("1101**********2X");
        staff.setCreateAt(LocalDateTime.of(2026, 8, 17, 10, 0));
        when(users.selectById(42L)).thenReturn(staff);
        when(users.updateStatus(eq(42L), eq(3), eq(1), any(LocalDateTime.class)))
                .thenReturn(1, 0);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("satoken:refresh-version:42")).thenReturn(1L);
        StpLogic stp = mock(StpLogic.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        when(audits.insert(any(AuditLog.class))).thenReturn(1);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.nextId()).thenReturn(100L, 101L, 102L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 12, 0));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), audits, users, ids,
                time, mock(IdCardCipher.class), mock(IdCardHasher.class), redis, stp,
                new ReserveXProperties(), tx);

        var condition = HttpPreconditions.requireVersion("\"3\"");
        AuthService.StaffView result = service.setStaffBanned(42L, true, 7L, condition);

        assertEquals(1, result.status());
        assertEquals(4, result.version());
        var order = inOrder(users, values, stp);
        order.verify(users).updateStatus(eq(42L), eq(3), eq(1), any(LocalDateTime.class));
        order.verify(values).increment("satoken:refresh-version:42");
        order.verify(stp).logout(42L);

        BizException stale = assertThrows(BizException.class,
                () -> service.setStaffBanned(42L, true, 7L, condition));
        assertEquals(ErrorCode.PRECONDITION_FAILED, stale.getErrorCode());
        verify(values, times(1)).increment("satoken:refresh-version:42");
        verify(stp, times(1)).logout(42L);

        ArgumentCaptor<AuditLog> auditRows = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits, times(3)).insert(auditRows.capture());
        assertEquals(List.of("BAN_USER_REQUESTED", "BAN_USER", "BAN_USER_REQUESTED"),
                auditRows.getAllValues().stream().map(AuditLog::getAction).toList());
        assertTrue(auditRows.getAllValues().stream()
                .allMatch(row -> row.getOperatorId() == 7L && row.getTargetId() == 42L));
    }

    @Test
    void staffStatusEndpointCannotTargetAdmin() {
        UserMapper users = mock(UserMapper.class);
        User admin = new User();
        admin.setUserId(1L);
        admin.setRole("ADMIN");
        admin.setStatus(0);
        when(users.selectById(1L)).thenReturn(admin);
        AuditLogMapper audits = mock(AuditLogMapper.class);

        AuthService service = authService(
                mock(EmailRouteMapper.class), mock(PhoneRouteMapper.class), mock(IdCardIdentityMapper.class), audits, users,
                mock(IdGenerator.class), mock(TimeSupport.class), mock(IdCardCipher.class),
                mock(IdCardHasher.class), mock(StringRedisTemplate.class), mock(StpLogic.class),
                new ReserveXProperties(), mock(PlatformTransactionManager.class));

        BizException error = assertThrows(BizException.class,
                () -> service.setStaffBanned(1L, true, 7L,
                        HttpPreconditions.requireVersion("*")));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
        verify(users, never()).updateStatus(any(), any(), any(), any());
        verify(audits, never()).insert(any(AuditLog.class));
    }

    private static void allowRefreshRate(StringRedisTemplate redis) {
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class),
                argThat(keys -> keys.size() == 1
                        && keys.getFirst().startsWith("ratelimit:refresh:ip:")),
                eq("60"), eq("120"));
    }

    private static AuthService authService(EmailRouteMapper emails,
                                           PhoneRouteMapper ignoredPhones,
                                           IdCardIdentityMapper ignoredIdentities,
                                           AuditLogMapper audits,
                                           UserMapper users,
                                           IdGenerator ids,
                                           TimeSupport time,
                                           IdCardCipher cipher,
                                           IdCardHasher hasher,
                                           StringRedisTemplate redis,
                                           StpLogic stp,
                                           ReserveXProperties props,
                                           PlatformTransactionManager tx) {
        return authService(emails, ignoredPhones, ignoredIdentities, audits, users, ids,
                time, cipher, hasher, redis, stp, props, tx,
                mock(RegistrationOutboxService.class));
    }

    private static AuthService authService(EmailRouteMapper emails,
                                           PhoneRouteMapper ignoredPhones,
                                           IdCardIdentityMapper ignoredIdentities,
                                           AuditLogMapper audits,
                                           UserMapper users,
                                           IdGenerator ids,
                                           TimeSupport time,
                                           IdCardCipher cipher,
                                           IdCardHasher hasher,
                                           StringRedisTemplate redis,
                                           StpLogic stp,
                                           ReserveXProperties props,
                                           PlatformTransactionManager tx,
                                           RegistrationOutboxService outboxes) {
        return new AuthService(emails, audits, users, ids, time, cipher, hasher,
                redis, stp, props, tx, outboxes);
    }
}
