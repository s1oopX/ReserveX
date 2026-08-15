package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.AuditLog;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationEvent;
import com.reservex.entity.VerificationLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** 60 秒动态 QR 与核销状态机。 */
@Service
public class QrService {

    private final ReservationMapper reservationMapper;
    private final VerificationLogMapper verificationMapper;
    private final ReservationEventMapper eventMapper;
    private final StateLogMapper stateLogMapper;
    private final AuditLogMapper auditMapper;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final StringRedisTemplate redis;
    private final TransactionTemplate singleTx;
    private final SecureRandom random = new SecureRandom();

    public QrService(ReservationMapper reservationMapper,
                     VerificationLogMapper verificationMapper,
                     ReservationEventMapper eventMapper,
                     StateLogMapper stateLogMapper,
                     AuditLogMapper auditMapper,
                     IdGenerator idGenerator,
                     TimeSupport time,
                     ReserveXProperties props,
                     StringRedisTemplate redis,
                     @Qualifier("singleTxManager") PlatformTransactionManager txManager) {
        this.reservationMapper = reservationMapper;
        this.verificationMapper = verificationMapper;
        this.eventMapper = eventMapper;
        this.stateLogMapper = stateLogMapper;
        this.auditMapper = auditMapper;
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.redis = redis;
        this.singleTx = new TransactionTemplate(txManager);
    }

    public QrView issue(long userId, long rno) {
        Reservation reservation = findOwn(userId, rno);
        if (reservation == null) {
            Object occupyUser = redis.opsForHash().get(ReservationService.occupyKey(rno), "user_id");
            if (occupyUser != null) {
                if (!Long.toString(userId).equals(occupyUser.toString())) {
                    throw BizException.of(ErrorCode.FORBIDDEN);
                }
                throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
            }
            Reservation other = reservationMapper.selectById(rno);
            if (other != null) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        requireReservable(reservation.getStatus());

        long exp = Instant.now().getEpochSecond() + props.getQr().getTtlSec();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String kid = props.getQr().getKeyId();
        String unsigned = "v1|" + kid + "|" + rno + "|" + exp + "|" + nonce;
        String payload = "v1." + kid + "." + rno + "." + exp + "." + nonce + "." + sign(unsigned);
        return new QrView(payload, exp);
    }

    public VerifyOutcome verifyScan(long staffId, String payload) {
        ParsedQr qr = parse(payload);
        if (!props.getQr().getAcceptedKeyIds().contains(qr.kid())
                || !props.getQr().getKeyId().equals(qr.kid())) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        String unsigned = "v1|" + qr.kid() + "|" + qr.rno() + "|" + qr.exp() + "|" + qr.nonce();
        byte[] expected = sign(unsigned).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, qr.signature().getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        if (qr.exp() < Instant.now().getEpochSecond()) {
            throw BizException.of(ErrorCode.QR_EXPIRED);
        }
        Reservation reservation = reservationMapper.selectById(qr.rno());
        if (reservation == null) {
            if (Boolean.TRUE.equals(redis.hasKey(ReservationService.occupyKey(qr.rno())))) {
                throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
            }
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return verify(staffId, reservation, 0, qr.nonce(), false);
    }

    public VerifyOutcome verifyManual(long staffId, long rno, String maskedConfirm) {
        Reservation reservation = reservationMapper.selectById(rno);
        if (reservation == null) {
            if (Boolean.TRUE.equals(redis.hasKey(ReservationService.occupyKey(rno)))) {
                throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
            }
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if (!reservation.getIdCardMasked().equals(maskedConfirm)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "脱敏证件号不匹配");
        }
        return verify(staffId, reservation, 1, null, true);
    }

    private VerifyOutcome verify(long staffId, Reservation reservation, int method,
                                 String nonce, boolean manual) {
        if (reservation.getStatus() == 1) {
            recordAttempt(reservation.getReservationNo(), staffId, method, nonce, 1);
            return new VerifyOutcome(true, previousResult(reservation));
        }
        if (reservation.getStatus() == 2) {
            recordAttempt(reservation.getReservationNo(), staffId, method, nonce, 2);
            throw BizException.of(ErrorCode.ALREADY_CANCELLED);
        }
        if (reservation.getStatus() == 3) {
            recordAttempt(reservation.getReservationNo(), staffId, method, nonce, 3);
            throw BizException.of(ErrorCode.ALREADY_EXPIRED);
        }

        LocalDateTime now = time.now();
        if (reservationMapper.verifyByNo(reservation.getReservationNo(), reservation.getVersion(), now) != 1) {
            Reservation latest = reservationMapper.selectById(reservation.getReservationNo());
            if (latest == null) {
                if (Boolean.TRUE.equals(redis.hasKey(
                        ReservationService.occupyKey(reservation.getReservationNo())))) {
                    throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
                }
                throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
            }
            return verify(staffId, latest, method, nonce, manual);
        }
        singleTx.executeWithoutResult(status -> {
            stateLogMapper.confirm("rx-" + reservation.getReservationNo());
            VerificationLog log = verification(reservation.getReservationNo(), staffId,
                    method, nonce, 0, now);
            verificationMapper.insertSuccess(log);
            eventMapper.insertIgnore(verifiedEvent(reservation.getReservationNo(), staffId, now));
            if (manual) {
                auditMapper.insert(manualAudit(reservation.getReservationNo(), staffId, now));
            }
        });
        return new VerifyOutcome(false,
                new VerifyView(reservation.getReservationNo(), "VERIFIED", now, staffId));
    }

    private VerifyView previousResult(Reservation reservation) {
        List<VerificationLog> logs = verificationMapper.selectByReservation(reservation.getReservationNo());
        VerificationLog first = logs.stream().filter(log -> log.getResult() == 0).findFirst().orElse(null);
        return new VerifyView(reservation.getReservationNo(), "ALREADY_VERIFIED",
                reservation.getVerifiedAt(), first == null ? null : first.getStaffId());
    }

    private void recordAttempt(long rno, long staffId, int method, String nonce, int result) {
        verificationMapper.insertAttempt(verification(rno, staffId, method, nonce, result, time.now()));
    }

    private VerificationLog verification(long rno, long staffId, int method,
                                         String nonce, int result, LocalDateTime now) {
        VerificationLog log = new VerificationLog();
        log.setVerifyId(idGenerator.nextId());
        log.setReservationNo(rno);
        log.setStaffId(staffId);
        log.setMethod(method);
        log.setQrNonce(result == 0 ? nonce : null);
        log.setAttemptNonce(result == 0 ? null : nonce);
        log.setResult(result);
        log.setVerifyTime(now);
        return log;
    }

    private ReservationEvent verifiedEvent(long rno, long staffId, LocalDateTime now) {
        ReservationEvent event = new ReservationEvent();
        event.setEventId("verified-" + rno);
        event.setReservationNo(rno);
        event.setEventType("VERIFIED");
        event.setFromStatus(0);
        event.setToStatus(1);
        event.setOperatorType("STAFF");
        event.setOperatorId(staffId);
        event.setRequestId(requestId("verified-" + rno));
        event.setEventTime(now);
        return event;
    }

    private AuditLog manualAudit(long rno, long staffId, LocalDateTime now) {
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("STAFF");
        audit.setOperatorId(staffId);
        audit.setAction("MANUAL_VERIFY");
        audit.setTargetType("RESERVATION");
        audit.setTargetId(rno);
        audit.setAfter("{\"status\":\"VERIFIED\"}");
        audit.setRequestId(requestId("manual-verify-" + rno));
        audit.setCreateAt(now);
        return audit;
    }

    private Reservation findOwn(long userId, long rno) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getReservationNo, rno));
    }

    private static void requireReservable(int status) {
        switch (status) {
            case 0 -> { }
            case 1 -> throw BizException.of(ErrorCode.ALREADY_VERIFIED);
            case 2 -> throw BizException.of(ErrorCode.ALREADY_CANCELLED);
            case 3 -> throw BizException.of(ErrorCode.ALREADY_EXPIRED);
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private ParsedQr parse(String payload) {
        if (payload == null || payload.length() > 512) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        String[] parts = payload.split("\\.", -1);
        if (parts.length != 6 || !"v1".equals(parts[0]) || !parts[4].matches("[0-9a-f]{32}")) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        try {
            return new ParsedQr(parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                    parts[4], parts[5]);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getQr().getHmacKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("QR HMAC 初始化失败", e);
        }
    }

    private static String requestId(String fallback) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null || requestId.isBlank() ? fallback : requestId;
    }

    private record ParsedQr(String kid, long rno, long exp, String nonce, String signature) {
    }

    public record QrView(String payload, long exp) {
    }

    public record VerifyView(Long reservationNo, String status, LocalDateTime verifyTime, Long staffId) {
    }

    public record VerifyOutcome(boolean alreadyVerified, VerifyView view) {
    }
}
