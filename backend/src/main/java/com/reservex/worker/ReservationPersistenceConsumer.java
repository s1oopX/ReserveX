package com.reservex.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationEvent;
import com.reservex.entity.StateLog;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.ConsumedEventMapper;
import com.reservex.mapper.single.IdCardRouteMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.message.CompensateRollbackMessage;
import com.reservex.message.ReservationCreatedMessage;
import com.reservex.service.ReservationService;
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
    private final StateLogMapper stateLogMapper;
    private final IdCardRouteMapper routeMapper;
    private final ReservationEventMapper eventMapper;
    private final SlotBucketMapper bucketMapper;
    private final ConsumedEventMapper consumedMapper;
    private final StringRedisTemplate redis;
    private final RocketMQTemplate rocketMQ;
    private final TimeSupport time;
    private final String consumerGroup;
    private final TransactionTemplate singleTx;

    public ReservationPersistenceConsumer(ReservationMapper reservationMapper,
                                          StateLogMapper stateLogMapper,
                                          IdCardRouteMapper routeMapper,
                                          ReservationEventMapper eventMapper,
                                          SlotBucketMapper bucketMapper,
                                          ConsumedEventMapper consumedMapper,
                                          StringRedisTemplate redis,
                                          RocketMQTemplate rocketMQ,
                                          TimeSupport time,
                                          ReserveXProperties props,
                                          @Qualifier("singleTxManager") PlatformTransactionManager txManager) {
        this.reservationMapper = reservationMapper;
        this.stateLogMapper = stateLogMapper;
        this.routeMapper = routeMapper;
        this.eventMapper = eventMapper;
        this.bucketMapper = bucketMapper;
        this.consumedMapper = consumedMapper;
        this.redis = redis;
        this.rocketMQ = rocketMQ;
        this.time = time;
        this.consumerGroup = props.getConsumer().getGroups().get("persistence");
        this.singleTx = new TransactionTemplate(txManager);
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
        if (consumedMapper.existsBy(consumerGroup, message.eventId()) > 0) {
            cleanup(message.reservationNo());
            return;
        }
        String occupyKey = ReservationService.occupyKey(message.reservationNo());
        Map<Object, Object> occupy = redis.opsForHash().entries(occupyKey);
        if (occupy.isEmpty()) {
            Reservation existing = findReservation(message.userId(), message.reservationNo());
            if (existing != null) {
                cleanup(message.reservationNo());
                return;
            }
            throw new IllegalStateException("occupy 已丢失 rno=" + message.reservationNo());
        }

        int targetStatus = "1".equals(string(occupy.get("cancelled"))) ? 2
                : "1".equals(string(occupy.get("expired"))) ? 3 : 0;
        String xid = "rx-" + message.reservationNo();
        StateLog prior = stateLogMapper.selectById(xid);
        if (prior != null && prior.getStatus() == 3) {
            targetStatus = 2;
        }
        insertReservation(message, targetStatus);

        try {
            int finalTargetStatus = targetStatus;
            singleTx.executeWithoutResult(status -> persistSingle(message, xid, finalTargetStatus));
        } catch (QuotaConflict e) {
            reservationMapper.invalidateByNo(message.reservationNo(), time.now());
            CompensateRollbackMessage rollback = new CompensateRollbackMessage(
                    "cr-" + message.reservationNo(), message.reservationNo(), message.dupKey(),
                    message.bucketKey(), message.slotFullKey(), "ID_CARD_ROUTE_CONFLICT",
                    message.requestId());
            rocketMQ.syncSend("compensate-rollback", rollback);
            singleTx.executeWithoutResult(status -> {
                stateLogMapper.insertOrCancel(xid, Long.toString(message.reservationNo()));
                consumedMapper.markConsumed(consumerGroup, message.eventId(), time.now());
            });
            log.warn("持久配额冲突，预约已回滚 rno={} owner={}",
                    message.reservationNo(), e.owner);
            return;
        }

        // 末尾复查窗口期取消，避免消费者第一次读标记后才发生的取消被漏掉。
        if ("1".equals(string(redis.opsForHash().get(occupyKey, "cancelled")))
                && reservationMapper.cancelByNo(message.reservationNo(), time.now()) == 1) {
            singleTx.executeWithoutResult(status -> {
                stateLogMapper.cancel(xid);
                eventMapper.insertIgnore(event(message, "cancelled-" + message.reservationNo(),
                        "CANCELLED", 0, 2, "USER", message.userId(), time.now()));
            });
        }
        cleanup(message.reservationNo());
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

    private void persistSingle(ReservationCreatedMessage message, String xid, int targetStatus) {
        stateLogMapper.insertTry(xid, Long.toString(message.reservationNo()));
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
            return;
        }

        eventMapper.insertIgnore(event(message, "created-" + message.reservationNo(),
                "CREATED", null, targetStatus, "SYSTEM", null,
                fromEpochMillis(message.createEpochMillis())));
        if (bucketMapper.incrOccupied(message.slotId(), message.bucketNo()) != 1) {
            throw new IllegalStateException("slot_bucket 不存在 slotId=" + message.slotId()
                    + " bucket=" + message.bucketNo());
        }
        if (targetStatus != 0) {
            stateLogMapper.cancel(xid);
        }
        consumedMapper.markConsumed(consumerGroup, message.eventId(), time.now());
    }

    private Reservation findReservation(long userId, long rno) {
        return reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getReservationNo, rno));
    }

    private ReservationEvent event(ReservationCreatedMessage message, String eventId,
                                   String type, Integer from, int to, String operatorType,
                                   Long operatorId, LocalDateTime at) {
        ReservationEvent event = new ReservationEvent();
        event.setEventId(eventId);
        event.setReservationNo(message.reservationNo());
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperatorType(operatorType);
        event.setOperatorId(operatorId);
        event.setRequestId(message.requestId());
        event.setEventTime(at);
        return event;
    }

    private void cleanup(long rno) {
        redis.opsForZSet().remove(ReservationService.PENDING_KEY, Long.toString(rno));
        redis.delete(ReservationService.occupyKey(rno));
    }

    private LocalDateTime fromEpochSecond(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), time.zone());
    }

    private LocalDateTime fromEpochMillis(long epoch) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch), time.zone());
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
