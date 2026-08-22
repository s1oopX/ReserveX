package com.reservex.worker;

import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.StateLog;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.ConsumedEventMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.message.TimeoutExpireMessage;
import com.reservex.service.ReservationTransitionOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.MDC;

import java.time.LocalDateTime;

/** 逐条过期收口；异常不 ACK,依靠 timeout 重投覆盖 CAS 后崩溃窗口。 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "timeout", consumerGroup = "${reservex.consumer.groups.timeout}")
public class TimeoutExpireConsumer implements RocketMQListener<TimeoutExpireMessage> {

    private final ReservationMapper reservationMapper;
    private final ReservationTransitionOutboxMapper outboxMapper;
    private final ReservationTransitionOutboxService transitionOutbox;
    private final ConsumedEventMapper consumedMapper;
    private final StateLogMapper stateLogMapper;
    private final TimeSupport time;
    private final String persistenceGroup;
    private final TransactionTemplate shardingTx;

    public TimeoutExpireConsumer(ReservationMapper reservationMapper,
                                 ReservationTransitionOutboxMapper outboxMapper,
                                 ReservationTransitionOutboxService transitionOutbox,
                                 ConsumedEventMapper consumedMapper,
                                 StateLogMapper stateLogMapper,
                                 TimeSupport time,
                                 ReserveXProperties props,
                                 @Qualifier("shardingTxManager") PlatformTransactionManager txManager) {
        this.reservationMapper = reservationMapper;
        this.outboxMapper = outboxMapper;
        this.transitionOutbox = transitionOutbox;
        this.consumedMapper = consumedMapper;
        this.stateLogMapper = stateLogMapper;
        this.time = time;
        this.persistenceGroup = props.getConsumer().getGroups().get("persistence");
        this.shardingTx = new TransactionTemplate(txManager);
    }

    @Override
    public void onMessage(TimeoutExpireMessage message) {
        MDC.put(RequestIdFilter.MDC_KEY, message.requestId());
        try {
            expire(message);
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

    private void expire(TimeoutExpireMessage message) {
        if (consumedMapper.existsBy(persistenceGroup, "rc-" + message.reservationNo()) == 0) {
            throw new IllegalStateException("persistence 尚未完成 rno=" + message.reservationNo());
        }
        String xid = "rx-" + message.reservationNo();
        StateLog state = stateLogMapper.selectById(xid);
        if (state != null && state.getStatus() == 4) {
            throw new IllegalStateException("人工回滚仲裁中 rno=" + message.reservationNo());
        }
        if (state != null && state.getStatus() == 3) {
            // Cancel/rollback intent already won in the single store. The persistence
            // consumer owns applying that terminal intent to the sharded row.
            return;
        }
        LocalDateTime now = time.now();
        ReservationTransitionOutbox outbox = expiredTransition(message,
                message.validUntil() == null ? now : message.validUntil(), now);
        Boolean expired = shardingTx.execute(tx -> {
            int changed = reservationMapper.expireByNo(
                    message.userId(), message.reservationNo(), now);
            if (changed != 1) {
                return false;
            }
            outboxMapper.insert(outbox);
            return true;
        });
        if (!Boolean.TRUE.equals(expired)) {
            return;
        }
        transitionOutbox.tryPublish(outbox);
        log.info("预约已过期 rno={}", message.reservationNo());
    }

    private static ReservationTransitionOutbox expiredTransition(
            TimeoutExpireMessage message, LocalDateTime eventTime, LocalDateTime createAt) {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("expired-" + message.reservationNo());
        outbox.setUserId(message.userId());
        outbox.setReservationNo(message.reservationNo());
        outbox.setEventType("EXPIRED");
        outbox.setOperatorType("SYSTEM");
        outbox.setManual(false);
        outbox.setRequestId(message.requestId());
        outbox.setEventTime(eventTime);
        outbox.setCreateAt(createAt);
        return outbox;
    }
}
