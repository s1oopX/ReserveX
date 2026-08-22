package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.Slot;
import com.reservex.entity.StateLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QrServiceTest {

    private static final long USER_ID = 11L;
    private static final long RNO = 22L;
    private static final String HMAC_KEY = "test-only-qr-hmac-key";
    private static final String OLD_HMAC_KEY = "test-only-old-qr-hmac-key";
    private static final LocalDate DATE = LocalDate.of(2026, 8, 16);
    private static final LocalDateTime NOW = DATE.atTime(10, 0);

    @Test
    void issuedPayloadHasValidHmacAndRejectsTampering() throws Exception {
        Fixture fixture = fixture();

        QrService.QrView qr = fixture.service.issue(USER_ID, RNO);
        String[] parts = qr.payload().split("\\.");
        String unsigned = String.join("|", parts[0], parts[1], parts[2], parts[3], parts[4]);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        assertThat(parts).hasSize(6);
        assertThat(parts[5]).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8))));
        assertThat(qr.exp()).isEqualTo(NOW.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()
                + fixture.props.getQr().getTtlSec());

        String tampered = qr.payload().replace("." + RNO + ".", ".23.");
        assertThatThrownBy(() -> fixture.service.verifyScan(99L, tampered))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QR_INVALID));
    }

    @Test
    void casMissWithDisappearedReservationReturnsBusinessError() {
        Fixture fixture = fixture();
        QrService.QrView qr = fixture.service.issue(USER_ID, RNO);
        when(fixture.reservationMapper.selectById(RNO))
                .thenReturn(fixture.reservation)
                .thenReturn(null);
        when(fixture.reservationMapper.verifyByNo(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.verifyScan(99L, qr.payload()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND));
    }

    @Test
    void successfulVerifyPersistsOutboxBeforeBestEffortPublish() {
        Fixture fixture = fixture();
        when(fixture.reservationMapper.verifyByNo(USER_ID, RNO, 0, NOW)).thenReturn(1);
        when(fixture.idGenerator.nextId()).thenReturn(901L);

        QrService.QrView qr = fixture.service.issue(USER_ID, RNO);
        assertThat(fixture.service.verifyScan(99L, qr.payload()).alreadyVerified()).isFalse();

        ArgumentCaptor<ReservationTransitionOutbox> captor =
                ArgumentCaptor.forClass(ReservationTransitionOutbox.class);
        verify(fixture.outboxMapper).insert(captor.capture());
        ReservationTransitionOutbox outbox = captor.getValue();
        assertThat(outbox.getTransitionId()).isEqualTo("verified-" + RNO);
        assertThat(outbox.getUserId()).isEqualTo(USER_ID);
        assertThat(outbox.getOperatorId()).isEqualTo(99L);
        verify(fixture.transitionOutbox).tryPublish(outbox);
    }

    /**
     * D4:QR 载荷解析的边界守卫。格式不符一律 QR_INVALID,不能抛 NPE/ArrayIndex
     * 冒到用户侧(那会走 500 白页,核销台唯一证据丢失)。
     */
    @Test
    void malformedPayloadsAreRejectedAsQrInvalid() {
        Fixture fixture = fixture();
        for (String bad : new String[]{
                null,                       // null 守卫
                "",                         // 空串
                "v1",                       // 段数不足
                "v2.kid.22.99999.0123456789abcdef0123456789abcdef.sig", // 版本号非 v1
                "v1.kid.abc.99999.0123456789abcdef0123456789abcdef.sig", // rno 非数字
                "v1.kid.22.abc.0123456789abcdef0123456789abcdef.sig",   // exp 非数字
                "v1.kid.22.99999.short.sig",                             // nonce 非 32 位 hex
                "v1.kid.22.99999.0123456789abcdef0123456789abcdef.sig.extra", // 段数超 6
        }) {
            assertThatThrownBy(() -> fixture.service.verifyScan(99L, bad))
                    .as("payload=%s 应判 QR_INVALID", bad)
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QR_INVALID));
        }
    }

    /**
     * D4:过期码与篡改签名是两类不同故障,不能混用错误码 ——
     * 过期(QR_EXPIRED)提示用户刷新,无效(QR_INVALID)是疑似伪造/扫码错误,
     * 核销台据此分清"游客该刷新码"还是"在试攻击"。
     */
    @Test
    void expiredQrReportsExpiryNotInvalid() {
        Fixture fixture = fixture();
        // 构造一个已过期的合法签名码:exp = 1(1970 年已过)。
        long expiredEpoch = 1L;
        String nonce = "0123456789abcdef0123456789abcdef"; // 32 hex
        String unsigned = "v1|" + propsKeyId() + "|" + RNO + "|" + expiredEpoch + "|" + nonce;
        String sig = signWithTestKey(unsigned);
        String payload = "v1." + propsKeyId() + "." + RNO + "." + expiredEpoch + "." + nonce + "." + sig;

        assertThatThrownBy(() -> fixture.service.verifyScan(99L, payload))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QR_EXPIRED));
    }

    @Test
    void acceptedPreviousKeyRemainsVerifiableDuringRotation() {
        Fixture fixture = fixture();
        String oldKeyId = "qr-v0";
        fixture.props.getQr().getKeys().put(oldKeyId, OLD_HMAC_KEY);
        fixture.props.getQr().getAcceptedKeyIds().add(oldKeyId);
        fixture.reservation.setStatus(1);

        long exp = NOW.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond() + 60;
        String payload = payloadAt(oldKeyId, OLD_HMAC_KEY, exp);

        assertThat(fixture.service.verifyScan(99L, payload).alreadyVerified()).isTrue();

        fixture.props.getQr().getAcceptedKeyIds().remove(oldKeyId);
        assertThatThrownBy(() -> fixture.service.verifyScan(99L, payload))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QR_INVALID));
    }

    @Test
    void futureSessionCannotIssueOrVerify() {
        Fixture fixture = fixture();
        fixture.slot.setSlotHour(11);

        assertThatThrownBy(() -> fixture.service.issue(USER_ID, RNO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_STARTED));

        String payload = payloadAt(NOW.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond() + 60);
        assertThatThrownBy(() -> fixture.service.verifyScan(99L, payload))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_STARTED));
        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "1234"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_STARTED));
        verify(fixture.reservationMapper, never()).verifyByNo(any(), any(), any(), any());
    }

    @Test
    void expiredReservationCannotIssueOrVerifyBeforeScannerCatchesUp() {
        Fixture fixture = fixture();
        fixture.reservation.setValidUntil(NOW.minusSeconds(1));

        assertThatThrownBy(() -> fixture.service.issue(USER_ID, RNO))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXPIRED));

        String payload = payloadAt(NOW.atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond() + 60);
        assertThatThrownBy(() -> fixture.service.verifyScan(99L, payload))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXPIRED));
        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "1234"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXPIRED));
        verify(fixture.reservationMapper, never()).verifyByNo(any(), any(), any(), any());
    }

    @Test
    void reservationRowIsNotVerifiableBeforePersistenceTransactionCompletes() {
        Fixture fixture = fixture();
        when(fixture.stateLogMapper.selectById("rx-" + RNO)).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.verifyManual(
                99L, RNO, "1234"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_CONFIRMING));
        verify(fixture.reservationMapper, never()).verifyByNo(any(), any(), any(), any());
    }

    @Test
    void manualVerificationRequiresOnlyMatchingLastFourDigits() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "123"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "9999"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        ArgumentCaptor<com.reservex.entity.VerificationLog> attempts =
                ArgumentCaptor.forClass(com.reservex.entity.VerificationLog.class);
        verify(fixture.verificationMapper, org.mockito.Mockito.times(2))
                .insertAttempt(attempts.capture());
        assertThat(attempts.getAllValues()).allMatch(log -> log.getResult() == 4);
    }

    @Test
    void manualVerificationFailsClosedWhenAttemptLimitIsReached() {
        Fixture fixture = fixture();
        doReturn(0L).when(fixture.redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(), any(), any());

        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "1234"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
        verify(fixture.reservationMapper, never()).verifyByNo(any(), any(), any(), any());
        assertThatThrownBy(() -> fixture.service.verifyManual(99L, RNO, "9999"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    private static String propsKeyId() {
        return new ReserveXProperties().getQr().getKeyId();
    }

    private static String signWithTestKey(String content) {
        return sign(content, HMAC_KEY);
    }

    private static String sign(String content, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payloadAt(long exp) {
        return payloadAt(propsKeyId(), HMAC_KEY, exp);
    }

    private static String payloadAt(String keyId, String key, long exp) {
        String nonce = "0123456789abcdef0123456789abcdef";
        String unsigned = "v1|" + keyId + "|" + RNO + "|" + exp + "|" + nonce;
        return "v1." + keyId + "." + RNO + "." + exp + "." + nonce + "."
                + sign(unsigned, key);
    }

    private static Fixture fixture() {
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        SlotMapper slotMapper = mock(SlotMapper.class);
        Reservation reservation = new Reservation();
        reservation.setReservationNo(RNO);
        reservation.setUserId(USER_ID);
        reservation.setSlotId(33L);
        reservation.setSlotDate(DATE);
        reservation.setValidUntil(DATE.atTime(12, 0));
        reservation.setIdCardMasked("310***********1234");
        reservation.setStatus(0);
        reservation.setVersion(0);
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(reservationMapper.selectById(RNO)).thenReturn(reservation);

        Slot slot = new Slot();
        slot.setSlotId(33L);
        slot.setSlotDate(DATE);
        slot.setSlotHour(9);
        slot.setValidUntil(DATE.atTime(12, 0));
        when(slotMapper.selectById(33L)).thenReturn(slot);

        ReserveXProperties props = new ReserveXProperties();
        props.getQr().getKeys().put(props.getQr().getKeyId(), HMAC_KEY);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(NOW);
        when(time.toEpochSecond(any(LocalDateTime.class))).thenAnswer(invocation ->
                invocation.<LocalDateTime>getArgument(0).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond());
        StateLogMapper stateLogMapper = mock(StateLogMapper.class);
        StateLog state = new StateLog();
        state.setXid("rx-" + RNO);
        state.setStatus(1);
        when(stateLogMapper.selectById(state.getXid())).thenReturn(state);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(txManager).commit(any());
        ReservationTransitionOutboxMapper outboxMapper =
                mock(ReservationTransitionOutboxMapper.class);
        ReservationTransitionOutboxService transitionOutbox =
                mock(ReservationTransitionOutboxService.class);
        IdGenerator idGenerator = mock(IdGenerator.class);
        VerificationLogMapper verificationMapper = mock(VerificationLogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class),
                any(List.class), any(), any(), any());
        QrService service = new QrService(
                reservationMapper,
                slotMapper,
                verificationMapper,
                stateLogMapper,
                outboxMapper,
                transitionOutbox,
                idGenerator,
                time,
                props,
                redis,
                txManager);
        return new Fixture(service, reservationMapper, stateLogMapper, outboxMapper,
                transitionOutbox, idGenerator, verificationMapper, redis,
                reservation, slot, props);
    }

    private record Fixture(QrService service, ReservationMapper reservationMapper,
                           StateLogMapper stateLogMapper,
                           ReservationTransitionOutboxMapper outboxMapper,
                           ReservationTransitionOutboxService transitionOutbox,
                           IdGenerator idGenerator, VerificationLogMapper verificationMapper,
                           StringRedisTemplate redis,
                           Reservation reservation, Slot slot, ReserveXProperties props) {
    }
}
