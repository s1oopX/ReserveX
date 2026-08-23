package com.reservex.service;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.session.SaSession;
import com.google.common.util.concurrent.RateLimiter;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.id.IdGenerator;
import com.reservex.lua.LuaScripts;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.ReservationCreatedMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** 用户抢号入口：校验和扣减只访问 Redis，主表由 MQ 异步落库。 */
@Slf4j
@Service
public class ReservationService {

    public static final String PENDING_KEY = "pending:persist";

    private final IdGenerator idGenerator;
    private final TimeSupport time;
    private final ReserveXProperties props;
    private final LuaScripts lua;
    private final StringRedisTemplate redis;
    private final RocketMQTemplate rocketMQ;
    private final StpLogic stpLogic;
    private final SlotService slotService;
    private final ReservationMapper reservationMapper;
    private final ReservationTransitionOutboxMapper outboxMapper;
    private final ReservationTransitionOutboxService transitionOutbox;
    private final StateLogMapper stateLogMapper;
    private final TransactionTemplate shardingTx;
    private final RateLimiter localLimiter;
    private final CaptchaService captchaService;
    private final StuckReservationMapper stuckMapper;

    public ReservationService(IdGenerator idGenerator,
                              TimeSupport time,
                              ReserveXProperties props,
                              LuaScripts lua,
                              StringRedisTemplate redis,
                              RocketMQTemplate rocketMQ,
                              StpLogic stpLogic,
                              SlotService slotService,
                              ReservationMapper reservationMapper,
                              ReservationTransitionOutboxMapper outboxMapper,
                              ReservationTransitionOutboxService transitionOutbox,
                              StateLogMapper stateLogMapper,
                              CaptchaService captchaService,
                              StuckReservationMapper stuckMapper,
                              @Qualifier("shardingTxManager") PlatformTransactionManager txManager) {
        this.idGenerator = idGenerator;
        this.time = time;
        this.props = props;
        this.lua = lua;
        this.redis = redis;
        this.rocketMQ = rocketMQ;
        this.stpLogic = stpLogic;
        this.slotService = slotService;
        this.reservationMapper = reservationMapper;
        this.outboxMapper = outboxMapper;
        this.transitionOutbox = transitionOutbox;
        this.stateLogMapper = stateLogMapper;
        this.captchaService = captchaService;
        this.stuckMapper = stuckMapper;
        this.shardingTx = new TransactionTemplate(txManager);
        this.localLimiter = RateLimiter.create(props.getRatelimit().getApiLocalRps());
    }

    public GrabResult grab(long userId, long slotId, String captchaToken) {
        if (!localLimiter.tryAcquire()) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }
        if (Boolean.TRUE.equals(redis.hasKey("ban:" + userId))) {
            throw BizException.of(ErrorCode.ACCOUNT_BANNED);
        }

        // D4 风控:被风控的用户必须带有效 captchaToken,否则要求验证码。
        // 正常用户无此标记 → 零额外 round-trip,不违反 2 round-trip 硬约束。
        if (captchaService.isCaptchaRequired(userId)) {
            if (captchaToken == null || captchaToken.isBlank()) {
                throw BizException.of(ErrorCode.CAPTCHA_REQUIRED);
            }
            // captchaToken 格式:{uuid}:{input},由前端拼好。
            int sep = captchaToken.indexOf(':');
            if (sep <= 0 || sep == captchaToken.length() - 1) {
                throw BizException.of(ErrorCode.CAPTCHA_INVALID);
            }
            String captchaKey = captchaToken.substring(0, sep);
            String captchaInput = captchaToken.substring(sep + 1);
            if (!captchaService.verify(captchaKey, captchaInput)) {
                throw BizException.of(ErrorCode.CAPTCHA_INVALID);
            }
        }

        Map<Object, Object> meta = redis.opsForHash().entries(metaKey(slotId));
        if (meta.isEmpty()) {
            slotService.ensureCached(slotId);
            meta = redis.opsForHash().entries(metaKey(slotId));
        }
        if (meta.isEmpty()) {
            throw BizException.of(ErrorCode.SLOT_NOT_FOUND);
        }
        if (!"1".equals(value(meta, "released"))) {
            throw BizException.of(ErrorCode.SLOT_NOT_RELEASED);
        }
        long validUntilEpoch = parseLong(meta, "valid_until");
        if (validUntilEpoch < Instant.now().getEpochSecond()) {
            throw BizException.of(ErrorCode.SLOT_ENDED);
        }

        String idCardHash = tokenExtra("idCardHash");
        String idCardMasked = tokenExtra("idCardMasked");
        LocalDate slotDate = LocalDate.parse(value(meta, "slot_date"));
        int slotHour = parseInt(meta, "slot_hour");
        int bucketCount = parseInt(meta, "bucket_count");
        long reservationNo = idGenerator.nextId();
        int primary = (Long.hashCode(reservationNo) & 0x7fffffff) % bucketCount;

        String dupKey = "dup:" + slotDate + ":" + idCardHash;
        String fullKey = "slot:full:" + slotId;
        long endTtl = time.ttlUntilEndOfDay(slotDate, props.getRedisKey().getDupTtlCapDays());
        long createMillis = time.now().atZone(time.zone()).toInstant().toEpochMilli();
        // D5:限流 KEYS 折叠进 grab.lua 末尾,保持 2 round-trip。
        // KEYS 顺序:桶 keys(环形) → ratelimit:user → ratelimit:slot。
        List<Object> keys = new ArrayList<>(bucketCount + 2);
        for (int i = 0; i < bucketCount; i++) {
            keys.add(bucketKey(slotId, (primary + i) % bucketCount));
        }
        keys.add("ratelimit:user:" + userId);
        keys.add("ratelimit:slot:" + slotId);
        Long result = lua.evalLong(LuaScripts.Script.GRAB, keys,
                Long.toString(reservationNo), Long.toString(slotId), Long.toString(userId),
                dupKey, Long.toString(endTtl), slotDate.toString(), Integer.toString(slotHour),
                Long.toString(validUntilEpoch), idCardMasked, idCardHash, Long.toString(createMillis),
                PENDING_KEY, fullKey, Long.toString(endTtl),
                Integer.toString(props.getRatelimit().getUserRedisRps()),
                Integer.toString(props.getRatelimit().getSlotRedisRps()));
        if (result == null || result == 0) {
            // D4:售罄是疑似刷单的失败,累加风控计数。
            captchaService.recordGrabFailure(userId);
            throw BizException.of(ErrorCode.SLOT_FULL);
        }
        if (result == -1) {
            Long existingReservationNo = duplicateReservationNo(dupKey);
            if (existingReservationNo != null
                    && isOwnDuplicate(userId, slotId, existingReservationNo)) {
                return new GrabResult(existingReservationNo, false);
            }
            // D4:配额已用同样计风控(短时间内反复尝试同证)。
            captchaService.recordGrabFailure(userId);
            throw BizException.of(ErrorCode.QUOTA_USED);
        }
        if (result == -2) {
            throw BizException.of(ErrorCode.RATE_LIMITED);
        }

        // D4:抢号成功清除风控标记(用户已正常完成预约,不再需要验证码)。
        captchaService.clearRiskOnSuccess(userId);

        String occupyKey = occupyKey(reservationNo);
        Object actualBucket = redis.opsForHash().get(occupyKey, "bucket_no");
        if (actualBucket == null) {
            // Lua 已成功，pending scanner 会补投；不能把成功说成失败让用户重复点击。
            log.error("抢号成功但读不到 occupy.bucket_no rno={}", reservationNo);
            return new GrabResult(reservationNo, true);
        }
        int bucketNo;
        try {
            bucketNo = Integer.parseInt(actualBucket.toString());
        } catch (NumberFormatException e) {
            // Redis 预占已经成功；保留 pending/occupy 交给扫描器隔离，不能让客户端重抢。
            redis.persist(occupyKey);
            log.error("抢号成功但 occupy.bucket_no 非法 rno={}", reservationNo, e);
            return new GrabResult(reservationNo, true);
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null || requestId.isBlank()) {
            requestId = "rc-" + reservationNo;
        }
        ReservationCreatedMessage message = new ReservationCreatedMessage(
                "rc-" + reservationNo, reservationNo, userId, slotId, slotDate.toString(),
                slotHour, bucketNo, idCardHash, idCardMasked, validUntilEpoch, createMillis,
                requestId, dupKey, bucketKey(slotId, bucketNo), fullKey);
        try {
            rocketMQ.syncSend("reservation-created", message);
        } catch (RuntimeException e) {
            // 已向用户返回的预约不能随传输故障过期；补投成功后 scanner 会恢复 TTL。
            redis.persist(occupyKey);
            log.error("预约消息发送失败，等待 pending scanner 补投 rno={}", reservationNo, e);
        }
        return new GrabResult(reservationNo, true);
    }

    private Long duplicateReservationNo(String dupKey) {
        String value = redis.opsForValue().get(dupKey);
        if (value == null) {
            return null;
        }
        try {
            long reservationNo = Long.parseLong(value);
            return reservationNo > 0 ? reservationNo : null;
        } catch (NumberFormatException e) {
            log.error("dup 值不是有效预约号");
            return null;
        }
    }

    private boolean isOwnDuplicate(long userId, long slotId, long reservationNo) {
        Map<Object, Object> occupy = redis.opsForHash().entries(occupyKey(reservationNo));
        if (!occupy.isEmpty()) {
            if ("1".equals(string(occupy.get("rollback_pending")))) {
                return false;
            }
            return Long.toString(userId).equals(string(occupy.get("user_id")))
                    && Long.toString(slotId).equals(string(occupy.get("slot_id")));
        }
        Reservation persisted = findOwn(userId, reservationNo);
        return persisted != null && persisted.getSlotId() == slotId;
    }

    public List<ReservationView> mine(long userId) {
        List<Reservation> rows = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateAt));
        List<ReservationView> result = new ArrayList<>(rows.size());
        Set<Long> seen = new HashSet<>();
        for (Reservation row : rows) {
            result.add(toView(row));
            seen.add(row.getReservationNo());
        }

        // 消费窗口通常小于 1 秒；从 pending 索引补最近在途记录，不全库 SCAN occupy:*。
        Set<String> pending = redis.opsForZSet().reverseRange(PENDING_KEY, 0,
                Math.max(0, props.getPending().getScanPageSize() - 1));
        if (pending != null) {
            for (String raw : pending) {
                long rno;
                try {
                    rno = Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (seen.contains(rno)) {
                    continue;
                }
                ReservationView view = occupyView(rno, userId, false);
                if (view != null) {
                    result.add(view);
                }
            }
        }
        for (com.reservex.entity.StuckReservation stuck : stuckMapper.selectByUser(userId)) {
            if (!seen.contains(stuck.getReservationNo())) {
                result.add(stuckView(stuck));
            }
        }
        result.sort((a, b) -> b.createAt().compareTo(a.createAt()));
        return result;
    }

    /**
     * staff 今日工作台:今日场次的预约列表(广播两库归并)。
     * 按 slotId 查非分片键会广播,低频管理操作可接受(见 ReservationMapper 注释)。
     * 脱敏返回,不暴露 idCardHash 明文关联。
     */
    public List<StaffReservationView> listToday(SlotService slotService) {
        List<Reservation> rows = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSlotDate, time.today())
                .orderByAsc(Reservation::getSlotId));
        List<StaffReservationView> result = new ArrayList<>(rows.size());
        for (Reservation row : rows) {
            SlotService.SlotView slot = slotService.getSlot(row.getSlotId());
            result.add(new StaffReservationView(row.getReservationNo(), row.getSlotId(), row.getSlotDate(),
                    slot.slotHour(), statusName(row.getStatus()), row.getVersion(), row.getCreateAt(),
                    row.getVerifiedAt()));
        }
        return result;
    }

    public ReservationView detail(long userId, long rno) {
        Reservation row = findOwn(userId, rno);
        if (row != null) {
            return toView(row);
        }
        com.reservex.entity.StuckReservation stuck = stuckMapper.selectById(rno);
        if (stuck != null && Long.valueOf(userId).equals(stuck.getUserId())) {
            return stuckView(stuck);
        }
        ReservationView occupy = occupyView(rno, userId, true);
        if (occupy != null) {
            return occupy;
        }
        throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
    }

    public void cancel(long userId, long rno, HttpPreconditions.VersionCondition condition) {
        Reservation row = findOwn(userId, rno);
        if (row != null) {
            requireVersion(condition, row.getVersion());
            switch (row.getStatus()) {
                case 1 -> throw BizException.of(ErrorCode.ALREADY_VERIFIED);
                case 2 -> { return; }
                case 3 -> throw BizException.of(ErrorCode.ALREADY_EXPIRED);
                default -> {
                    LocalDateTime now = time.now();
                    if (cancelPersisted(row, now)) {
                        return;
                    }
                    Reservation latest = findOwn(userId, rno);
                    if (latest != null && latest.getStatus() != 0) {
                        throw statusError(latest.getStatus());
                    }
                    throw BizException.of(ErrorCode.ALREADY_EXPIRED);
                }
            }
        }

        requireVersion(condition, 0);
        com.reservex.entity.StuckReservation stuck = stuckMapper.selectById(rno);
        if (stuck != null && Long.valueOf(userId).equals(stuck.getUserId())) {
            throw BizException.of(ErrorCode.STATE_CONFLICT);
        }
        String occupyKey = occupyKey(rno);
        LocalDateTime cancelTime = time.now();
        Long marked = lua.evalLong(LuaScripts.Script.MARK_CANCEL, List.of(occupyKey),
                Long.toString(userId), requestId("cancelled-" + rno),
                Long.toString(time.toEpochSecond(cancelTime)));
        if (Long.valueOf(-1).equals(marked)) {
            throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
        }
        if (Long.valueOf(2).equals(marked)) {
            throw BizException.of(ErrorCode.ALREADY_EXPIRED);
        }
        Reservation raced;
        if (Long.valueOf(0).equals(marked)) {
            // Consumer cleanup deletes occupy only after persistence; close that race in the DB.
            raced = findOwn(userId, rno);
            if (raced == null) {
                throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
            }
        } else if (Long.valueOf(1).equals(marked)) {
            stateLogMapper.insertOrCancel("rx-" + rno, Long.toString(rno));
            // The consumer may have persisted from an older snapshot; finish its cancellation here.
            raced = findOwn(userId, rno);
            if (raced == null) {
                return;
            }
        } else {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        switch (raced.getStatus()) {
            case 1 -> throw BizException.of(ErrorCode.ALREADY_VERIFIED);
            case 2 -> { return; }
            case 3 -> throw BizException.of(ErrorCode.ALREADY_EXPIRED);
            default -> {
                if (cancelPersisted(raced, cancelTime)) {
                    return;
                }
                Reservation latest = findOwn(userId, rno);
                if (latest != null && latest.getStatus() != 0) {
                    if (latest.getStatus() == 2) {
                        return;
                    }
                    throw statusError(latest.getStatus());
                }
                throw BizException.of(ErrorCode.ALREADY_EXPIRED);
            }
        }
    }

    private boolean cancelPersisted(Reservation reservation, LocalDateTime now) {
        ReservationTransitionOutbox outbox = cancellationTransition(reservation, now);
        Boolean committed = shardingTx.execute(status -> {
            if (reservationMapper.cancelByNo(reservation.getUserId(),
                    reservation.getReservationNo(), reservation.getVersion(), now) != 1) {
                return false;
            }
            outboxMapper.insert(outbox);
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            transitionOutbox.tryPublish(outbox);
            return true;
        }
        return false;
    }

    private ReservationTransitionOutbox cancellationTransition(Reservation reservation,
                                                               LocalDateTime now) {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("cancelled-" + reservation.getReservationNo());
        outbox.setUserId(reservation.getUserId());
        outbox.setReservationNo(reservation.getReservationNo());
        outbox.setEventType("CANCELLED");
        outbox.setOperatorType("USER");
        outbox.setOperatorId(reservation.getUserId());
        outbox.setManual(false);
        outbox.setRequestId(requestId("cancelled-" + reservation.getReservationNo()));
        outbox.setEventTime(now);
        outbox.setCreateAt(now);
        return outbox;
    }

    private static void requireVersion(HttpPreconditions.VersionCondition condition, int version) {
        if (!condition.matches(version)) {
            throw BizException.of(ErrorCode.PRECONDITION_FAILED);
        }
    }

    private Reservation findOwn(long userId, long rno) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getReservationNo, rno));
    }

    private ReservationView toView(Reservation row) {
        SlotService.SlotView slot = slotService.getSlot(row.getSlotId());
        return new ReservationView(row.getReservationNo(), row.getSlotId(), row.getSlotDate(),
                slot.slotHour(), statusName(row.getStatus()), row.getVersion(), row.getCreateAt(),
                row.getVerifiedAt(), row.getIdCardMasked());
    }

    private ReservationView occupyView(long rno, long userId, boolean rejectForeign) {
        Map<Object, Object> occupy = redis.opsForHash().entries(occupyKey(rno));
        if (occupy.isEmpty()) {
            return null;
        }
        if (!Long.toString(userId).equals(value(occupy, "user_id"))) {
            if (rejectForeign) {
                throw BizException.of(ErrorCode.RESERVATION_NOT_FOUND);
            }
            return null;
        }
        try {
            String status = "1".equals(string(occupy.get("cancelled"))) ? "CANCELLED"
                    : "1".equals(string(occupy.get("expired"))) ? "EXPIRED" : "PENDING";
            long createMillis = Long.parseLong(value(occupy, "create_ts"));
            return new ReservationView(rno, Long.parseLong(value(occupy, "slot_id")),
                    LocalDate.parse(value(occupy, "slot_date")),
                    Integer.parseInt(value(occupy, "slot_hour")), status, 0,
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(createMillis), time.zone()), null,
                    string(occupy.get("id_card_masked")));
        } catch (RuntimeException e) {
            log.error("occupy 载荷损坏 rno={}", rno, e);
            if (rejectForeign) {
                throw new BizException(ErrorCode.SERVICE_DEGRADED, "预约在途数据损坏，请联系管理员");
            }
            return null;
        }
    }

    private ReservationView stuckView(com.reservex.entity.StuckReservation stuck) {
        SlotService.SlotView slot = slotService.getSlot(stuck.getSlotId());
        String status = Integer.valueOf(0).equals(stuck.getStatus())
                || Integer.valueOf(4).equals(stuck.getStatus())
                ? "REVIEW_REQUIRED" : "FAILED";
        return new ReservationView(stuck.getReservationNo(), stuck.getSlotId(), stuck.getSlotDate(),
                slot.slotHour(), status, 0, stuck.getCreateAt(), null, null);
    }

    private static String requestId(String fallback) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId == null || requestId.isBlank() ? fallback : requestId;
    }

    private static BizException statusError(int status) {
        return switch (status) {
            case 1 -> BizException.of(ErrorCode.ALREADY_VERIFIED);
            case 2 -> BizException.of(ErrorCode.ALREADY_CANCELLED);
            case 3 -> BizException.of(ErrorCode.ALREADY_EXPIRED);
            default -> BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    private static String statusName(int status) {
        return switch (status) {
            case 0 -> "CONFIRMED";
            case 1 -> "VERIFIED";
            case 2 -> "CANCELLED";
            case 3 -> "EXPIRED";
            default -> throw new IllegalStateException("未知预约状态 " + status);
        };
    }

    private String tokenExtra(String name) {
        SaSession session = stpLogic.getTokenSession();
        Object value = session == null ? null : session.get(name);
        if (value == null || value.toString().isBlank()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return value.toString();
    }

    private static String value(Map<Object, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            throw BizException.of(ErrorCode.SERVICE_DEGRADED);
        }
        return value.toString();
    }

    private static int parseInt(Map<Object, Object> meta, String key) {
        return Integer.parseInt(value(meta, key));
    }

    private static long parseLong(Map<Object, Object> meta, String key) {
        return Long.parseLong(value(meta, key));
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    public static String occupyKey(long rno) {
        return "occupy:" + rno;
    }

    public static String bucketKey(long slotId, int bucketNo) {
        return "slot:" + slotId + ":b:" + bucketNo;
    }

    private static String metaKey(long slotId) {
        return "slot:meta:" + slotId;
    }

    public record GrabResult(Long reservationNo, boolean created) {
    }

    public record ReservationView(Long reservationNo, Long slotId, LocalDate slotDate,
                                  Integer slotHour, String status, Integer version,
                                  LocalDateTime createAt, LocalDateTime verifyTime, String idCardMasked) {
    }

    public record StaffReservationView(Long reservationNo, Long slotId, LocalDate slotDate,
                                        Integer slotHour, String status, Integer version,
                                        LocalDateTime createAt, LocalDateTime verifyTime) {
    }
}
