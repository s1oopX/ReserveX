package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.Slot;
import com.reservex.entity.StateLog;
import com.reservex.entity.VerificationLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** 60 秒动态 QR 与核销状态机。 */
@Service
public class QrService {

    private static final int MANUAL_STAFF_ATTEMPTS = 5;
    private static final int MANUAL_RESERVATION_ATTEMPTS = 20;
    private static final int MANUAL_ATTEMPT_WINDOW_SEC = 600;
    private static final DefaultRedisScript<Long> CHECK_MANUAL_RATE = new DefaultRedisScript<>("""
            local staff = redis.call('INCR', KEYS[1])
            if staff == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local reservation = redis.call('INCR', KEYS[2])
            if reservation == 1 then redis.call('EXPIRE', KEYS[2], ARGV[1]) end
            if staff > tonumber(ARGV[2]) or reservation > tonumber(ARGV[3]) then return 0 end
            return 1
            """, Long.class);

    private final ReservationMapper reservationMapper;
    private final SlotMapper slotMapper;
    private final VerificationLogMapper verificationMapper;
    private final StateLogMapper stateLogMapper;
    private final ReservationTransitionOutboxMapper outboxMapper;
    private final ReservationTransitionOutboxService transitionOutbox;
    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final StringRedisTemplate redis;
    private final TransactionTemplate shardingTx;
    private final SecureRandom random = new SecureRandom();

    public QrService(ReservationMapper reservationMapper,
                     SlotMapper slotMapper,
                     VerificationLogMapper verificationMapper,
                     StateLogMapper stateLogMapper,
                     ReservationTransitionOutboxMapper outboxMapper,
                     ReservationTransitionOutboxService transitionOutbox,
                     IdGenerator idGenerator,
                     TimeSupport time,
                     ReserveXProperties props,
                     StringRedisTemplate redis,
                     @Qualifier("shardingTxManager") PlatformTransactionManager txManager) {
        this.reservationMapper = reservationMapper;
        this.slotMapper = slotMapper;
        this.verificationMapper = verificationMapper;
        this.stateLogMapper = stateLogMapper;
        this.outboxMapper = outboxMapper;
        this.transitionOutbox = transitionOutbox;
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.redis = redis;
        this.shardingTx = new TransactionTemplate(txManager);
    }

    public QrView issue(long userId, long rno) {
        Reservation reservation = findOwn(userId, rno);
        if (reservation == null) {
            Object occupyUser = redis.opsForHash().get(ReservationService.occupyKey(rno), "user_id");
            if (occupyUser != null) {
                if (!Long.toString(userId).equals(occupyUser.toString())) {
                    throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
                }
                throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
            }
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        requireReservable(reservation.getStatus());
        requirePersistenceComplete(reservation);
        LocalDateTime now = time.now();
        requireActiveWindow(reservation, now);

        long exp = time.toEpochSecond(now) + props.getQr().getTtlSec();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String kid = props.getQr().getKeyId();
        String unsigned = "v1|" + kid + "|" + rno + "|" + exp + "|" + nonce;
        String payload = "v1." + kid + "." + rno + "." + exp + "." + nonce + "." + sign(unsigned, kid);
        return new QrView(payload, exp);
    }

    public VerifyOutcome verifyScan(long staffId, String payload) {
        ParsedQr qr = parse(payload);
        if (!props.getQr().getAcceptedKeyIds().contains(qr.kid())) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        String unsigned = "v1|" + qr.kid() + "|" + qr.rno() + "|" + qr.exp() + "|" + qr.nonce();
        byte[] expected = sign(unsigned, qr.kid()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, qr.signature().getBytes(StandardCharsets.US_ASCII))) {
            throw BizException.of(ErrorCode.QR_INVALID);
        }
        if (qr.exp() < time.toEpochSecond(time.now())) {
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
        requireManualAttemptAllowed(staffId, rno);
        if (maskedConfirm == null || !maskedConfirm.matches("[0-9]{3}[0-9Xx]")) {
            recordAttempt(rno, staffId, 1, null, 4);
            throw new BizException(ErrorCode.BAD_REQUEST, "证件号末四位格式不正确");
        }
        String stored = reservation.getIdCardMasked();
        if (stored == null || stored.length() < 4
                || !stored.regionMatches(true, stored.length() - 4,
                maskedConfirm, 0, 4)) {
            recordAttempt(rno, staffId, 1, null, 4);
            throw new BizException(ErrorCode.BAD_REQUEST, "证件号末四位不匹配");
        }
        return verify(staffId, reservation, 1, null, true);
    }

    private void requireManualAttemptAllowed(long staffId, long rno) {
        String slot = "{" + rno + "}";
        Long allowed = redis.execute(CHECK_MANUAL_RATE, List.of(
                        "ratelimit:manual-verify:" + slot + ":staff:" + staffId,
                        "ratelimit:manual-verify:" + slot + ":all"),
                Integer.toString(MANUAL_ATTEMPT_WINDOW_SEC),
                Integer.toString(MANUAL_STAFF_ATTEMPTS),
                Integer.toString(MANUAL_RESERVATION_ATTEMPTS));
        if (!Long.valueOf(1L).equals(allowed)) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
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

        requirePersistenceComplete(reservation);
        LocalDateTime now = time.now();
        requireActiveWindow(reservation, now);
        ReservationTransitionOutbox outbox = transition(reservation, staffId, method, nonce, manual, now);
        Boolean committed = shardingTx.execute(status -> {
            if (reservationMapper.verifyByNo(reservation.getUserId(),
                    reservation.getReservationNo(), reservation.getVersion(), now) != 1) {
                return false;
            }
            outboxMapper.insert(outbox);
            return true;
        });
        if (!Boolean.TRUE.equals(committed)) {
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
        transitionOutbox.tryPublish(outbox);
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

    private ReservationTransitionOutbox transition(Reservation reservation, long staffId,
                                                   int method, String nonce, boolean manual,
                                                   LocalDateTime now) {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("verified-" + reservation.getReservationNo());
        outbox.setUserId(reservation.getUserId());
        outbox.setReservationNo(reservation.getReservationNo());
        outbox.setEventType("VERIFIED");
        outbox.setOperatorType("STAFF");
        outbox.setOperatorId(staffId);
        outbox.setMethod(method);
        outbox.setQrNonce(nonce);
        outbox.setManual(manual);
        outbox.setVerificationId(idGenerator.nextId());
        outbox.setAuditId(manual ? idGenerator.nextId() : null);
        outbox.setRequestId(requestId("verified-" + reservation.getReservationNo()));
        outbox.setEventTime(now);
        outbox.setCreateAt(now);
        return outbox;
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

    private void requireActiveWindow(Reservation reservation, LocalDateTime now) {
        Slot slot = slotMapper.selectById(reservation.getSlotId());
        if (slot == null) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        LocalDateTime startsAt = slot.getSlotDate().atTime(slot.getSlotHour(), 0);
        if (now.isBefore(startsAt)) {
            throw BizException.of(ErrorCode.RESERVATION_NOT_STARTED);
        }
        if (now.isAfter(reservation.getValidUntil())) {
            throw BizException.of(ErrorCode.ALREADY_EXPIRED);
        }
    }

    private void requirePersistenceComplete(Reservation reservation) {
        StateLog state = stateLogMapper.selectById("rx-" + reservation.getReservationNo());
        if (state == null || state.getStatus() != 1) {
            throw BizException.of(ErrorCode.RESERVATION_CONFIRMING);
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

    private String sign(String content, String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getQr().getKeys().get(keyId).getBytes(StandardCharsets.UTF_8),
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
