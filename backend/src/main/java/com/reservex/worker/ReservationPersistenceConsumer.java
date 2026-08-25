package com.reservex.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationEvent;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.StateLog;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.ConsumedEventMapper;
import com.reservex.mapper.single.IdCardRouteMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.CompensateRollbackMessage;
import com.reservex.message.ReservationCreatedMessage;
import com.reservex.metrics.ReserveXMetrics;
import com.reservex.service.ReservationService;
import com.reservex.service.ReservationTransitionOutboxService;
import com.reservex.service.RollbackService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/** ReservationCreated 五阶段落库；跨库不伪装成一个本地事务。 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "reservation-created",
        consumerGroup = "${reservex.consumer.groups.persistence}")
public class ReservationPersistenceConsumer implements RocketMQListener<ReservationCreatedMessage> {

    private final ReservationMapper reservationMapper;
    private final ReservationTransitionOutboxMapper outboxMapper;
    private final ReservationTransitionOutboxService transitionOutbox;
    private final StateLogMapper stateLogMapper;
    private final IdCardRouteMapper routeMapper;
    private final ReservationEventMapper eventMapper;
    private final SlotBucketMapper bucketMapper;
    private final ConsumedEventMapper consumedMapper;
    private final StuckReservationMapper stuckMapper;
    private final StringRedisTemplate redis;
    private final RocketMQTemplate rocketMQ;
    private final TimeSupport time;
    private final String consumerGroup;
    private final TransactionTemplate singleTx;
    private final TransactionTemplate shardingTx;
    private final ReserveXMetrics metrics;

    public ReservationPersistenceConsumer(ReservationMapper reservationMapper,
                                          ReservationTransitionOutboxMapper outboxMapper,
                                          ReservationTransitionOutboxService transitionOutbox,
                                          StateLogMapper stateLogMapper,
                                          IdCardRouteMapper routeMapper,
                                          ReservationEventMapper eventMapper,
                                          SlotBucketMapper bucketMapper,
                                          ConsumedEventMapper consumedMapper,
                                          StuckReservationMapper stuckMapper,
                                          StringRedisTemplate redis,
                                          RocketMQTemplate rocketMQ,
                                          TimeSupport time,
                                          ReserveXProperties props,
                                          @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager,
                                          @Qualifier("shardingTxManager") PlatformTransactionManager shardingTxManager,
                                          ReserveXMetrics metrics) {
        this.reservationMapper = reservationMapper;
        this.outboxMapper = outboxMapper;
        this.transitionOutbox = transitionOutbox;
        this.stateLogMapper = stateLogMapper;
        this.routeMapper = routeMapper;
        this.eventMapper = eventMapper;
        this.bucketMapper = bucketMapper;
        this.consumedMapper = consumedMapper;
        this.stuckMapper = stuckMapper;
        this.redis = redis;
        this.rocketMQ = rocketMQ;
        this.time = time;
        this.consumerGroup = props.getConsumer().getGroups().get("persistence");
        this.singleTx = new TransactionTemplate(singleTxManager);
        this.shardingTx = new TransactionTemplate(shardingTxManager);
        this.metrics = metrics;
    }

    @Override
    public void onMessage(ReservationCreatedMessage message) {
        MDC.put(RequestIdFilter.MDC_KEY, message.requestId());
        try {
            persist(message);
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private void persist(ReservationCreatedMessage message) {
        validate(message);
        if (consumedMapper.existsBy(consumerGroup, message.eventId()) > 0) {
            String occupyKey = ReservationService.occupyKey(message.reservationNo());
            if ("1".equals(string(redis.opsForHash().get(occupyKey, "rollback_pending")))) {
                return;
            }
            applyLateCancellation(message, occupyKey);
            cleanupCompleted(message.reservationNo());
            return;
        }
        String occupyKey = ReservationService.occupyKey(message.reservationNo());
        Map<Object, Object> occupy = redis.opsForHash().entries(occupyKey);
        if (occupy.isEmpty()) {
            // Redis 不是创建事件的唯一证据。grab 成功后同步发送的持久 MQ 消息已带齐
            // 落库字段；拒绝它会让已返回给用户的预约号永久消失。
            log.warn("occupy 已丢失，按可靠消息恢复落库 rno={}", message.reservationNo());
        }

        int targetStatus = "1".equals(string(occupy.get("cancelled"))) ? 2
                : "1".equals(string(occupy.get("expired")))
                || !fromEpochSecond(message.validUntilEpoch()).isAfter(time.now()) ? 3 : 0;
        String xid = "rx-" + message.reservationNo();
        StateLog prior = stateLogMapper.selectById(xid);
        if (prior != null && (prior.getStatus() == 3 || prior.getStatus() == 4)) {
            targetStatus = 2;
        }
        insertReservation(message, targetStatus);

        try {
            int finalTargetStatus = targetStatus;
            Boolean persisted = singleTx.execute(status ->
                    persistSingle(message, xid, finalTargetStatus, occupyKey, occupy));
            if (!Boolean.TRUE.equals(persisted)) {
                reservationMapper.invalidateByNo(message.reservationNo(), time.now());
                return;
            }
        } catch (QuotaConflict e) {
            int invalidated = reservationMapper.invalidateByNo(message.reservationNo(), time.now());
            if (invalidated == 0) {
                Reservation latest = findReservation(message.userId(), message.reservationNo());
                if (latest == null || (latest.getStatus() != 2 && latest.getStatus() != 3)) {
                    throw new IllegalStateException(
                            "配额冲突预约未能作废，拒绝回补库存 rno=" + message.reservationNo(), e);
                }
            }
            CompensateRollbackMessage rollback = new CompensateRollbackMessage(
                    "cr-" + message.reservationNo(), message.reservationNo(), message.dupKey(),
                    message.bucketKey(), message.slotFullKey(), "ID_CARD_ROUTE_CONFLICT",
                    message.requestId());
            // consumed 重投只能在补偿完成后清 occupy；补偿 Lua 是该标记的唯一删除者。
            redis.opsForHash().put(occupyKey, "rollback_pending", "1");
            rocketMQ.syncSend("compensate-rollback", rollback);
            metrics.compensateTriggered(ReserveXMetrics.REASON_ID_CARD_ROUTE_CONFLICT);
            singleTx.executeWithoutResult(status -> {
                stateLogMapper.insertOrCancel(xid, Long.toString(message.reservationNo()));
                consumedMapper.markConsumed(consumerGroup, message.eventId(), time.now());
            });
            log.warn("持久配额冲突，预约已回滚 rno={} owner={}",
                    message.reservationNo(), e.owner);
            return;
        }

        applyLateCancellation(message, occupyKey);
        cleanupCompleted(message.reservationNo());
    }

    /** Replayed even after consumed_event commits, so a durable cancel intent cannot be skipped. */
    private void applyLateCancellation(ReservationCreatedMessage message, String occupyKey) {
        Map<Object, Object> latestOccupy = redis.opsForHash().entries(occupyKey);
        StateLog state = stateLogMapper.selectById("rx-" + message.reservationNo());
        boolean requested = "1".equals(string(latestOccupy.get("cancelled")))
                || state != null && state.getStatus() == 3;
        if (!requested) {
            return;
        }
        LocalDateTime now = time.now();
        LocalDateTime requestedAt = cancellationTime(latestOccupy,
                state != null && state.getUpdateAt() != null ? state.getUpdateAt() : now);
        ReservationTransitionOutbox outbox = cancellationTransition(
                message, latestOccupy, requestedAt, now);
        Boolean cancelled = shardingTx.execute(status -> {
            if (reservationMapper.cancelByNo(
                    message.userId(), message.reservationNo(), 0, requestedAt) != 1) {
                return false;
            }
            outboxMapper.insert(outbox);
            return true;
        });
        if (Boolean.TRUE.equals(cancelled)) {
            transitionOutbox.tryPublish(outbox);
        }
    }

    private void insertReservation(ReservationCreatedMessage message, int status) {
        if (findReservation(message.userId(), message.reservationNo()) != null) {
            return;
        }
        Reservation reservation = new Reservation();
        reservation.setReservationNo(message.reservationNo());
        reservation.setUserId(message.userId());
        reservation.setSlotId(message.slotId());
        reservation.setSlotDate(LocalDate.parse(message.slotDate()));
        reservation.setBucketNo(message.bucketNo());
        reservation.setIdCardHash(message.idCardHash());
        reservation.setIdCardMasked(message.idCardMasked());
        reservation.setStatus(status);
        reservation.setValidUntil(fromEpochSecond(message.validUntilEpoch()));
        reservation.setCreateAt(fromEpochMillis(message.createEpochMillis()));
        reservation.setUpdateAt(reservation.getCreateAt());
        reservation.setVersion(0);
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException e) {
            if (findReservation(message.userId(), message.reservationNo()) == null) {
                throw e;
            }
        }
    }

    private boolean persistSingle(ReservationCreatedMessage message, String xid,
                                  int targetStatus, String occupyKey,
                                  Map<Object, Object> occupy) {
        stateLogMapper.insertTry(xid, Long.toString(message.reservationNo()));
        StateLog state = stateLogMapper.selectById(xid);
        if (state != null) {
            if (state.getStatus() == 4) {
                return false;
            }
            if (state.getStatus() == 3 && !Boolean.TRUE.equals(redis.hasKey(occupyKey))) {
                return false;
            }
        }
        try {
            routeMapper.tryInsertQuota(message.idCardHash(),
                    LocalDate.parse(message.slotDate()), message.reservationNo(),
                    fromEpochMillis(message.createEpochMillis()));
        } catch (DuplicateKeyException e) {
            // tryInsertQuota 用 INSERT(非 IGNORE):撞 PK 抛 DuplicateKeyException。
            // 语义等价于原 INSERT IGNORE 返回 0 的分支 —— 比对 owner 决定冲突或幂等。
            // InnoDB 单语句约束冲突不回滚事务,事务仍 active,可继续 select/markConsumed。
            Long owner = routeMapper.selectReservationNo(
                    message.idCardHash(), LocalDate.parse(message.slotDate()));
            if (!Objects.equals(owner, message.reservationNo())) {
                throw new QuotaConflict(owner);
            }
            // 同一 rno 已完成过阶段 2；当前只补幂等登记，不重复累加 occupied。
            consumedMapper.markConsumed(consumerGroup, message.eventId(), time.now());
            return true;
        }

        eventMapper.insertIgnore(event(message, "created-" + message.reservationNo(),
                "CREATED", null, targetStatus, "SYSTEM", null,
                fromEpochMillis(message.createEpochMillis())));
        if (targetStatus == 3) {
            // 抢号时已过期的 occupy 也要有独立 EXPIRED 审计事件；CREATED 的 to_status=3
            // 只能说明落库结果，不能替代业务状态迁移。
            eventMapper.insertIgnore(event(message, "expired-" + message.reservationNo(),
                    "EXPIRED", 0, 3, "SYSTEM", null, time.now()));
        }
        if (targetStatus == 2 && "1".equals(string(occupy.get("cancelled")))) {
            eventMapper.insertIgnore(event(message, "cancelled-" + message.reservationNo(),
                    "CANCELLED", 0, 2, "USER", message.userId(),
                    cancellationTime(occupy, time.now()),
                    fallback(string(occupy.get("cancel_request_id")), message.requestId())));
        }
        if (bucketMapper.incrOccupied(message.slotId(), message.bucketNo()) != 1) {
            throw new IllegalStateException("slot_bucket 不存在 slotId=" + message.slotId()
                    + " bucket=" + message.bucketNo());
        }
        if (targetStatus != 0) {
            stateLogMapper.cancel(xid);
        }
        consumedMapper.markConsumed(consumerGroup, message.eventId(), time.now());
        return true;
    }

    private Reservation findReservation(long userId, long rno) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getReservationNo, rno));
    }

    private ReservationEvent event(ReservationCreatedMessage message, String eventId,
                                   String type, Integer from, int to, String operatorType,
                                   Long operatorId, LocalDateTime at) {
        return event(message, eventId, type, from, to, operatorType, operatorId, at,
                message.requestId());
    }

    private ReservationEvent event(ReservationCreatedMessage message, String eventId,
                                   String type, Integer from, int to, String operatorType,
                                   Long operatorId, LocalDateTime at, String requestId) {
        ReservationEvent event = new ReservationEvent();
        event.setEventId(eventId);
        event.setReservationNo(message.reservationNo());
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperatorType(operatorType);
        event.setOperatorId(operatorId);
        event.setRequestId(requestId);
        event.setEventTime(at);
        return event;
    }

    private ReservationTransitionOutbox cancellationTransition(ReservationCreatedMessage message,
                                                               Map<Object, Object> occupy,
                                                               LocalDateTime eventTime,
                                                               LocalDateTime createAt) {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("cancelled-" + message.reservationNo());
        outbox.setUserId(message.userId());
        outbox.setReservationNo(message.reservationNo());
        outbox.setEventType("CANCELLED");
        outbox.setOperatorType("USER");
        outbox.setOperatorId(message.userId());
        outbox.setManual(false);
        outbox.setRequestId(fallback(string(occupy.get("cancel_request_id")), message.requestId()));
        outbox.setEventTime(eventTime);
        outbox.setCreateAt(createAt);
        return outbox;
    }

    private LocalDateTime cancellationTime(Map<Object, Object> occupy, LocalDateTime fallbackTime) {
        String epoch = string(occupy.get("cancelled_at"));
        return epoch == null ? fallbackTime : fromEpochSecond(Long.parseLong(epoch));
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void cleanup(long rno) {
        redis.opsForZSet().remove(ReservationService.PENDING_KEY, Long.toString(rno));
        redis.delete(ReservationService.occupyKey(rno));
    }

    private void cleanupCompleted(long rno) {
        int status = Boolean.TRUE.equals(redis.hasKey(RollbackService.doneKey(rno))) ? 2 : 3;
        stuckMapper.resolveAutomatically(rno, status, time.now());
        cleanup(rno);
    }

    private LocalDateTime fromEpochSecond(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), time.zone());
    }

    private LocalDateTime fromEpochMillis(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), time.zone());
    }

    private static void validate(ReservationCreatedMessage message) {
        String expectedBucket = ReservationService.bucketKey(message.slotId(), message.bucketNo());
        String expectedDup = "dup:" + message.slotDate() + ":" + message.idCardHash();
        if (message.reservationNo() <= 0 || message.userId() <= 0 || message.slotId() <= 0
                || message.bucketNo() < 0 || message.validUntilEpoch() <= 0
                || message.createEpochMillis() <= 0
                || !message.eventId().equals("rc-" + message.reservationNo())
                || !message.slotFullKey().equals("slot:full:" + message.slotId())
                || !message.bucketKey().equals(expectedBucket)
                || !message.dupKey().equals(expectedDup)
                || message.idCardHash() == null
                || !message.idCardHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("非法预约创建消息 rno=" + message.reservationNo());
        }
        LocalDate.parse(message.slotDate());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static final class QuotaConflict extends RuntimeException {
        private final Long owner;

        private QuotaConflict(Long owner) {
            this.owner = owner;
        }
    }
}
