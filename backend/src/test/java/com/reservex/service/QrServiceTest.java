package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QrServiceTest {

    private static final long USER_ID = 11L;
    private static final long RNO = 22L;
    private static final String HMAC_KEY = "test-only-qr-hmac-key";

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
        assertThat(qr.exp()).isBetween(Instant.now().getEpochSecond() + 58,
                Instant.now().getEpochSecond() + 61);

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
        when(fixture.reservationMapper.verifyByNo(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.verifyScan(99L, qr.payload()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_FOUND));
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

    private static String propsKeyId() {
        return new ReserveXProperties().getQr().getKeyId();
    }

    private static String signWithTestKey(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Fixture fixture() {
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        Reservation reservation = new Reservation();
        reservation.setReservationNo(RNO);
        reservation.setUserId(USER_ID);
        reservation.setStatus(0);
        reservation.setVersion(0);
        when(reservationMapper.selectOne(any())).thenReturn(reservation);

        ReserveXProperties props = new ReserveXProperties();
        props.getQr().setHmacKey(HMAC_KEY);
        QrService service = new QrService(
                reservationMapper,
                mock(VerificationLogMapper.class),
                mock(ReservationEventMapper.class),
                mock(StateLogMapper.class),
                mock(AuditLogMapper.class),
                mock(IdGenerator.class),
                new TimeSupport(props),
                props,
                mock(StringRedisTemplate.class),
                mock(PlatformTransactionManager.class));
        return new Fixture(service, reservationMapper, reservation);
    }

    private record Fixture(QrService service, ReservationMapper reservationMapper,
                           Reservation reservation) {
    }
}
