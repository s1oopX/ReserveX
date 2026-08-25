package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
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
import com.reservex.service.ReservationService;
import com.reservex.service.ReservationTransitionOutboxService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.reservex.metrics.ReserveXMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationPersistenceConsumerTest {

    private static final long RNO = 22L;
    private static final String GROUP = "cg-persistence";
    private static final String ID_HASH = "a".repeat(64);

    @Test
    void missingOccupyRecoversTheAcknowledgedReservationFromMq() {
        Fixture fixture = fixture();
        when(fixture.bucket.incrOccupied(33L, 2)).thenReturn(1);

        fixture.consumer.onMessage(message());

        ArgumentCaptor<Reservation> inserted = ArgumentCaptor.forClass(Reservation.class);
        verify(fixture.reservation).insert(inserted.capture());
        assertEquals(RNO, inserted.getValue().getReservationNo());
        assertEquals(0, inserted.getValue().getStatus());
        verify(fixture.route).tryInsertQuota(eq(ID_HASH), any(), eq(RNO), any());
        verify(fixture.stuck).resolveAutomatically(eq(RNO), eq(3), any());
    }

    @Test
    void existingReservationWithoutConsumedMarkerResumesTheSingleStorePhase() {
        Fixture fixture = fixture();
        Reservation existing = new Reservation();
        existing.setReservationNo(RNO);
        existing.setUserId(11L);
        when(fixture.reservation.selectOne(any())).thenReturn(existing);
        when(fixture.bucket.incrOccupied(33L, 2)).thenReturn(1);

        fixture.consumer.onMessage(message());

        verify(fixture.reservation, never()).insert(any(Reservation.class));
        verify(fixture.route).tryInsertQuota(eq(ID_HASH), any(), eq(RNO), any());
        verify(fixture.event).insertIgnore(argThat(event ->
                "CREATED".equals(event.getEventType()) && event.getReservationNo() == RNO));
        verify(fixture.bucket).incrOccupied(33L, 2);
        verify(fixture.consumed).markConsumed(eq(GROUP), eq("rc-22"), any());
    }

    @Test
    void consumedRedeliveryDoesNotDeleteOccupyWhileRollbackIsPending() {
        Fixture fixture = fixture();
        when(fixture.consumed.existsBy(GROUP, "rc-22")).thenReturn(1);
        when(fixture.hash.get(ReservationService.occupyKey(RNO), "rollback_pending"))
                .thenReturn("1");

        fixture.consumer.onMessage(message());

        verify(fixture.redis, never()).delete(ReservationService.occupyKey(RNO));
        verify(fixture.zset, never()).remove(ReservationService.PENDING_KEY, Long.toString(RNO));
    }

    @Test
    void consumedRedeliveryAppliesDurableCancellationBeforeCleanup() {
        Fixture fixture = fixture();
        when(fixture.consumed.existsBy(GROUP, "rc-22")).thenReturn(1);
        StateLog cancelled = new StateLog();
        cancelled.setStatus(3);
        cancelled.setUpdateAt(LocalDateTime.of(2026, 8, 16, 11, 59));
        when(fixture.stateLog.selectById("rx-" + RNO)).thenReturn(cancelled);
        when(fixture.reservation.cancelByNo(eq(11L), eq(RNO), eq(0), any())).thenReturn(1);

        fixture.consumer.onMessage(message());

        InOrder order = inOrder(fixture.reservation, fixture.outbox, fixture.redis);
        order.verify(fixture.reservation).cancelByNo(eq(11L), eq(RNO), eq(0), any());
        order.verify(fixture.outbox).insert(any(ReservationTransitionOutbox.class));
        order.verify(fixture.redis).delete(ReservationService.occupyKey(RNO));
    }

    @Test
    void failedLateCancellationDoesNotCleanOccupy() {
        Fixture fixture = fixture();
        when(fixture.consumed.existsBy(GROUP, "rc-22")).thenReturn(1);
        StateLog cancelled = new StateLog();
        cancelled.setStatus(3);
        when(fixture.stateLog.selectById("rx-" + RNO)).thenReturn(cancelled);
        when(fixture.reservation.cancelByNo(eq(11L), eq(RNO), eq(0), any())).thenReturn(1);
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(fixture.outbox).insert(any(ReservationTransitionOutbox.class));

        assertThrows(IllegalStateException.class, () -> fixture.consumer.onMessage(message()));

        verify(fixture.redis, never()).delete(ReservationService.occupyKey(RNO));
        verify(fixture.zset, never()).remove(ReservationService.PENDING_KEY, Long.toString(RNO));
    }

    @Test
    void quotaConflictMarksRollbackPendingBeforePublishingAndConsuming() {
        Fixture fixture = fixture();
        occupy(fixture);
        when(fixture.route.tryInsertQuota(any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("quota conflict"));
        when(fixture.route.selectReservationNo(any(), any())).thenReturn(99L);
        when(fixture.reservation.invalidateByNo(eq(RNO), any())).thenReturn(1);

        fixture.consumer.onMessage(message());

        InOrder order = inOrder(fixture.hash, fixture.rocketMQ, fixture.consumed);
        order.verify(fixture.consumed).existsBy(GROUP, "rc-22");
        order.verify(fixture.hash).put(ReservationService.occupyKey(RNO), "rollback_pending", "1");
        order.verify(fixture.rocketMQ).syncSend(eq("compensate-rollback"),
                any(CompensateRollbackMessage.class));
        order.verify(fixture.consumed).markConsumed(eq(GROUP), eq("rc-22"), any());
        // 回补是静默动作:没有这个计数器,「补偿偶发」和「补偿刷屏」在外部看不出区别。
        assertEquals(1d, fixture.registry
                .get(ReserveXMetrics.COMPENSATE_TRIGGERED)
                .tag("reason", ReserveXMetrics.REASON_ID_CARD_ROUTE_CONFLICT)
                .counter().count());
    }

    @Test
    void quotaConflictNeverRefundsStockWhenReservationCouldNotBeInvalidated() {
        Fixture fixture = fixture();
        occupy(fixture);
        Reservation verified = new Reservation();
        verified.setReservationNo(RNO);
        verified.setUserId(11L);
        verified.setStatus(1);
        when(fixture.reservation.selectOne(any())).thenReturn(verified);
        when(fixture.route.tryInsertQuota(any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("quota conflict"));
        when(fixture.route.selectReservationNo(any(), any())).thenReturn(99L);

        assertThrows(IllegalStateException.class, () -> fixture.consumer.onMessage(message()));

        verify(fixture.rocketMQ, never()).syncSend(eq("compensate-rollback"),
                any(CompensateRollbackMessage.class));
    }

    @Test
    void manualRollbackClaimStopsPersistenceBeforeQuotaAndBucketWrites() {
        Fixture fixture = fixture();
        occupy(fixture);
        StateLog claim = new StateLog();
        claim.setStatus(4);
        when(fixture.stateLog.selectById("rx-" + RNO)).thenReturn(claim);

        fixture.consumer.onMessage(message());

        verify(fixture.reservation).invalidateByNo(eq(RNO), any());
        verify(fixture.route, never()).tryInsertQuota(any(), any(), any(), any());
        verify(fixture.redis, never()).delete(ReservationService.occupyKey(RNO));
    }

    @Test
    void pendingCancellationGetsItsOwnAuditEvent() {
        Fixture fixture = fixture();
        when(fixture.hash.entries(ReservationService.occupyKey(RNO))).thenReturn(Map.ofEntries(
                Map.entry("user_id", "11"), Map.entry("slot_id", "33"),
                Map.entry("slot_date", "2026-08-16"), Map.entry("slot_hour", "14"),
                Map.entry("bucket_no", "2"), Map.entry("id_card_hash", ID_HASH),
                Map.entry("id_card_masked", "310***********1234"),
                Map.entry("valid_until", "1776000000"),
                Map.entry("create_ts", "1755321600000"),
                Map.entry("bucket", "slot:33:b:2"), Map.entry("cancelled", "1"),
                Map.entry("cancel_request_id", "cancel-request"),
                Map.entry("cancelled_at", "1786852800")));
        when(fixture.bucket.incrOccupied(33L, 2)).thenReturn(1);

        fixture.consumer.onMessage(message());

        verify(fixture.event).insertIgnore(argThat(event ->
                "CANCELLED".equals(event.getEventType())
                        && "cancel-request".equals(event.getRequestId())
                        && event.getOperatorId() == 11L));
    }

    @Test
    void cancellationArrivingDuringPersistenceIsCommittedBeforeCleanup() {
        Fixture fixture = fixture();
        Map<Object, Object> initial = occupyFields();
        Map<Object, Object> cancelled = new HashMap<>(initial);
        cancelled.put("cancelled", "1");
        cancelled.put("cancel_request_id", "cancel-request");
        cancelled.put("cancelled_at", "1786852800");
        when(fixture.hash.entries(ReservationService.occupyKey(RNO)))
                .thenReturn(initial, cancelled);
        when(fixture.bucket.incrOccupied(33L, 2)).thenReturn(1);
        when(fixture.reservation.cancelByNo(eq(11L), eq(RNO), eq(0), any())).thenReturn(1);

        fixture.consumer.onMessage(message());

        InOrder order = inOrder(fixture.hash, fixture.reservation, fixture.outbox,
                fixture.publisher, fixture.redis);
        order.verify(fixture.hash).entries(ReservationService.occupyKey(RNO));
        order.verify(fixture.hash).entries(ReservationService.occupyKey(RNO));
        order.verify(fixture.reservation).cancelByNo(eq(11L), eq(RNO), eq(0), any());
        order.verify(fixture.outbox).insert(argThat((ReservationTransitionOutbox outbox) ->
                "CANCELLED".equals(outbox.getEventType())
                        && "cancel-request".equals(outbox.getRequestId())));
        order.verify(fixture.publisher).tryPublish(any());
        order.verify(fixture.redis).delete(ReservationService.occupyKey(RNO));
    }

    private static void occupy(Fixture fixture) {
        when(fixture.hash.entries(ReservationService.occupyKey(RNO)))
                .thenReturn(occupyFields());
    }

    private static Map<Object, Object> occupyFields() {
        return Map.of(
                "user_id", "11", "slot_id", "33", "slot_date", "2026-08-16",
                "slot_hour", "14", "bucket_no", "2", "id_card_hash", ID_HASH,
                "id_card_masked", "310***********1234", "valid_until", "1787000000",
                "create_ts", "1755321600000", "bucket", "slot:33:b:2");
    }

    private static Fixture fixture() {
        ReservationMapper reservation = mock(ReservationMapper.class);
        ReservationTransitionOutboxMapper outbox = mock(ReservationTransitionOutboxMapper.class);
        ReservationTransitionOutboxService publisher = mock(ReservationTransitionOutboxService.class);
        StateLogMapper stateLog = mock(StateLogMapper.class);
        IdCardRouteMapper route = mock(IdCardRouteMapper.class);
        ReservationEventMapper event = mock(ReservationEventMapper.class);
        SlotBucketMapper bucket = mock(SlotBucketMapper.class);
        ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForZSet()).thenReturn(zset);

        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 16, 12, 0));
        when(time.zone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        ReserveXProperties props = new ReserveXProperties();
        props.getConsumer().getGroups().put("persistence", GROUP);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationPersistenceConsumer consumer = new ReservationPersistenceConsumer(
                reservation, outbox, publisher, stateLog, route, event, bucket, consumed, stuck,
                redis, rocketMQ, time, props, txManager, txManager, new ReserveXMetrics(registry));
        return new Fixture(consumer, reservation, outbox, publisher, stateLog, route, consumed,
                stuck, event, bucket, redis, hash, zset, rocketMQ, registry);
    }

    private static ReservationCreatedMessage message() {
        return new ReservationCreatedMessage(
                "rc-22", RNO, 11L, 33L, "2026-08-16", 14, 2,
                ID_HASH, "310***********1234", 1_787_000_000L,
                1_755_321_600_000L, "request-1", "dup:2026-08-16:" + ID_HASH,
                "slot:33:b:2", "slot:full:33");
    }

    private record Fixture(ReservationPersistenceConsumer consumer,
                           ReservationMapper reservation,
                           ReservationTransitionOutboxMapper outbox,
                           ReservationTransitionOutboxService publisher,
                           StateLogMapper stateLog,
                           IdCardRouteMapper route,
                           ConsumedEventMapper consumed,
                           StuckReservationMapper stuck,
                           ReservationEventMapper event,
                           SlotBucketMapper bucket,
                           StringRedisTemplate redis,
                           HashOperations<String, Object, Object> hash,
                           ZSetOperations<String, String> zset,
                           RocketMQTemplate rocketMQ,
                           SimpleMeterRegistry registry) {
    }
}
