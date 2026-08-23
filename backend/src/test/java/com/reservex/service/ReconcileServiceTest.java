package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.IdCardRoute;
import com.reservex.entity.AuditLog;
import com.reservex.entity.ReconcileLog;
import com.reservex.entity.Reservation;
import com.reservex.entity.Slot;
import com.reservex.entity.SlotBucket;
import com.reservex.entity.StateLog;
import com.reservex.entity.StuckReservation;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.IdCardRouteMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.mapper.single.SlotBucketMapper;
import com.reservex.mapper.single.SlotMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconcileServiceTest {

    private static final long RNO = 10L;

    @Test
    void stockReconciliationUsesOneBusinessDateAcrossMidnight() {
        LocalDate today = LocalDate.of(2026, 8, 18);
        SlotMapper slots = mock(SlotMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.today()).thenReturn(today, today.plusDays(1));
        when(time.now()).thenReturn(today.atStartOfDay());
        ReconcileService service = new ReconcileService(slots, mock(SlotBucketMapper.class),
                mock(ReservationMapper.class), mock(IdCardRouteMapper.class),
                mock(ReconcileLogMapper.class), mock(StuckReservationMapper.class),
                mock(StateLogMapper.class), mock(VerificationLogMapper.class),
                mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(IdGenerator.class), time, mock(RollbackService.class),
                new ReserveXProperties());

        service.reconcileStock();

        verify(time).today();
        verify(slots).selectByDate(today);
        verify(slots).selectByDate(today.plusDays(1));
    }

    @Test
    void stockReconciliationDetectsBucketMismatchWhenTotalsMatch() {
        LocalDate today = LocalDate.of(2026, 8, 18);
        Slot slot = new Slot();
        slot.setSlotId(99L);
        slot.setCapacity(20);
        slot.setBucketCount(2);
        slot.setReleased(1);

        SlotMapper slots = mock(SlotMapper.class);
        SlotBucketMapper buckets = mock(SlotBucketMapper.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        ReconcileLogMapper logs = mock(ReconcileLogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        IdGenerator ids = mock(IdGenerator.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.today()).thenReturn(today);
        when(time.now()).thenReturn(today.atTime(12, 0));
        when(slots.selectByDate(today)).thenReturn(List.of(slot));
        when(slots.selectByDate(today.plusDays(1))).thenReturn(List.of());
        when(buckets.selectBySlot(99L)).thenReturn(List.of(
                bucket(0, 10, 6), bucket(1, 10, 4)));
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of(ReservationService.bucketKey(99L, 0),
                ReservationService.bucketKey(99L, 1))))
                .thenReturn(List.of("5", "5"));
        when(reservations.selectCount(any())).thenReturn(10L);
        when(ids.nextId()).thenReturn(1L);

        ReconcileService service = new ReconcileService(slots, buckets, reservations,
                mock(IdCardRouteMapper.class), logs, mock(StuckReservationMapper.class),
                mock(StateLogMapper.class), mock(VerificationLogMapper.class), redis, ids,
                time, mock(RollbackService.class), new ReserveXProperties());

        service.reconcileStock();

        verify(logs).insertIgnore(org.mockito.ArgumentMatchers.argThat(
                (ReconcileLog row) -> row.getDiff() == 0
                        && "bucket-mismatch".equals(row.getFixAction())));
    }

    @Test
    void pendingIndexReconciliationUsesABoundedBatch() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.range(ReservationService.PENDING_KEY, 0, 499)).thenReturn(Set.of());
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 18, 12, 0));
        ReconcileService service = new ReconcileService(mock(SlotMapper.class),
                mock(SlotBucketMapper.class), mock(ReservationMapper.class),
                mock(IdCardRouteMapper.class), mock(ReconcileLogMapper.class),
                mock(StuckReservationMapper.class), mock(StateLogMapper.class),
                mock(VerificationLogMapper.class), redis, mock(IdGenerator.class),
                time, mock(RollbackService.class), new ReserveXProperties());

        service.reconcilePendingIndex();

        verify(zset).range(ReservationService.PENDING_KEY, 0, 499);
    }

    @Test
    void cancelledAndExpiredReservationsKeepTheirDailyQuotaRoutes() {
        LocalDate date = LocalDate.of(2026, 8, 17);
        IdCardRouteMapper routes = mock(IdCardRouteMapper.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        ReconcileLogMapper logs = mock(ReconcileLogMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.today()).thenReturn(date);
        when(time.now()).thenReturn(date.atTime(12, 0));

        List<IdCardRoute> routeRows = java.util.stream.LongStream.rangeClosed(1, 5)
                .mapToObj(rno -> route(rno, date, "hash-" + rno)).toList();
        when(routes.selectBySlotDate(date)).thenReturn(routeRows);
        for (int status = 0; status <= 3; status++) {
            when(reservations.selectById((long) status + 1))
                    .thenReturn(reservation((long) status + 1, date, "hash-" + (status + 1), status));
        }

        service(mock(StuckReservationMapper.class), mock(RollbackService.class), reservations,
                mock(StateLogMapper.class), routes, logs, time).reconcileRoute();

        verify(logs).insertIgnore(org.mockito.ArgumentMatchers.argThat(
                row -> row.getDiff() == 1));
    }

    @Test
    void rollbackResolvesOnlyAfterCompensationSucceeds() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        StuckReservation row = stuck();
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        StateLog claim = new StateLog();
        claim.setStatus(4);
        StateLog done = new StateLog();
        done.setStatus(3);
        when(stuck.selectById(RNO)).thenReturn(row);
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(stuck.transition(eq(RNO), eq(4), eq(2), eq(7L), any())).thenReturn(1);
        when(rollback.compensate(any())).thenReturn(true);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(claim, done);
        when(audits.insert((AuditLog) any(AuditLog.class))).thenReturn(1);

        ReconcileService service = new ReconcileService(mock(SlotMapper.class), mock(SlotBucketMapper.class),
                reservations, mock(IdCardRouteMapper.class), mock(ReconcileLogMapper.class), audits, stuck,
                stateLogs, mock(VerificationLogMapper.class), mock(StringRedisTemplate.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS), mock(IdGenerator.class), mock(TimeSupport.class),
                rollback, new ReserveXProperties());

        org.junit.jupiter.api.Assertions.assertEquals(1,
                service.handleAction("stuck", RNO, "rollback", 7L));

        verify(rollback).compensate(any());
        verify(stuck).transition(eq(RNO), eq(4), eq(2), eq(7L), any());
        verify(audits).insert(org.mockito.ArgumentMatchers.<AuditLog>argThat(
                audit -> "STUCK_ROLLBACK".equals(audit.getAction())
                        && RNO == audit.getTargetId()));
    }

    @Test
    void terminalReservationWithoutRollbackMarkerIsNeverCompensated() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        Reservation cancelled = new Reservation();
        cancelled.setStatus(2);
        when(stuck.selectById(RNO)).thenReturn(stuck());
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(reservations.selectById(RNO)).thenReturn(cancelled);

        ReconcileService service = service(stuck, rollback, reservations);

        assertThrows(BizException.class,
                () -> service.handleAction("stuck", RNO, "rollback", 7L));
        verify(rollback, never()).compensate(any());
    }

    @Test
    void failedCompensationLeavesStuckPending() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        StateLog claim = new StateLog();
        claim.setStatus(4);
        when(stuck.selectById(RNO)).thenReturn(stuck());
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(rollback.compensate(any())).thenReturn(false);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(claim);

        ReconcileService service = service(stuck, rollback, mock(ReservationMapper.class), stateLogs);

        assertThrows(BizException.class,
                () -> service.handleAction("stuck", RNO, "rollback", 7L));
        verify(stuck, never()).transition(eq(RNO), eq(4), eq(2), eq(7L), any());
    }

    @Test
    void auditFailureAfterRollbackReturnsStuckToPending() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        StateLog claim = new StateLog();
        claim.setStatus(4);
        StateLog done = new StateLog();
        done.setStatus(3);
        when(stuck.selectById(RNO)).thenReturn(stuck());
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(stuck.transition(eq(RNO), eq(4), eq(2), eq(7L), any())).thenReturn(1);
        when(stuck.transition(eq(RNO), eq(2), eq(0), eq(7L), any())).thenReturn(1);
        when(rollback.compensate(any())).thenReturn(true);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(claim, done);
        when(audits.insert((AuditLog) any(AuditLog.class))).thenReturn(0);

        ReconcileService service = new ReconcileService(mock(SlotMapper.class), mock(SlotBucketMapper.class),
                mock(ReservationMapper.class), mock(IdCardRouteMapper.class), mock(ReconcileLogMapper.class),
                audits, stuck, stateLogs, mock(VerificationLogMapper.class),
                mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(IdGenerator.class), mock(TimeSupport.class), rollback, new ReserveXProperties());

        assertThrows(BizException.class, () -> service.handleAction("stuck", RNO, "rollback", 7L));
        verify(stuck).transition(eq(RNO), eq(2), eq(0), eq(7L), any());
    }

    @Test
    void concurrentFinishIsReportedAsStateConflict() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        StateLog claim = new StateLog();
        claim.setStatus(4);
        StateLog done = new StateLog();
        done.setStatus(3);
        StuckReservation latest = stuck();
        latest.setStatus(4);
        when(stuck.selectById(RNO)).thenReturn(stuck(), latest);
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(stuck.transition(eq(RNO), eq(4), eq(2), eq(7L), any())).thenReturn(0);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(claim, done);
        when(rollback.compensate(any())).thenReturn(true);

        ReconcileService service = service(stuck, rollback, mock(ReservationMapper.class), stateLogs);

        BizException error = assertThrows(BizException.class,
                () -> service.handleAction("stuck", RNO, "rollback", 7L));
        assertEquals(ErrorCode.STATE_CONFLICT, error.getErrorCode());
    }

    @Test
    void startedPersistenceTransactionIsNeverCompensated() {
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        RollbackService rollback = mock(RollbackService.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        StateLog started = new StateLog();
        started.setStatus(1);
        when(stuck.selectById(RNO)).thenReturn(stuck());
        when(stuck.transition(eq(RNO), eq(0), eq(4), eq(7L), any())).thenReturn(1);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(started);

        ReconcileService service = service(stuck, rollback, reservations, stateLogs);

        assertThrows(BizException.class,
                () -> service.handleAction("stuck", RNO, "rollback", 7L));
        verify(rollback, never()).compensate(any());
    }

    @Test
    void dashboardCountsOnlyCurrentUnresolvedDiffs() {
        LocalDate today = LocalDate.of(2026, 8, 18);
        SlotMapper slots = mock(SlotMapper.class);
        ReservationMapper reservations = mock(ReservationMapper.class);
        ReconcileLogMapper logs = mock(ReconcileLogMapper.class);
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.today()).thenReturn(today);
        when(slots.selectByDate(today)).thenReturn(List.of());
        when(logs.countCurrentWithDiff(today)).thenReturn(3L);

        ReconcileService service = new ReconcileService(slots, mock(SlotBucketMapper.class),
                reservations, mock(IdCardRouteMapper.class), logs, stuck,
                mock(StateLogMapper.class), mock(VerificationLogMapper.class),
                mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(IdGenerator.class), time, mock(RollbackService.class),
                new ReserveXProperties());

        assertEquals(3L, service.dashboard().reconcileDiffCount());
        verify(logs).countCurrentWithDiff(today);
    }

    private static StuckReservation stuck() {
        StuckReservation row = new StuckReservation();
        row.setReservationNo(RNO);
        row.setSlotId(99L);
        row.setBucketKey("slot:bucket:99:2");
        row.setDupKey("dup:2026-08-16:id-card-hash");
        return row;
    }

    private static IdCardRoute route(long rno, LocalDate date, String hash) {
        IdCardRoute row = new IdCardRoute();
        row.setReservationNo(rno);
        row.setSlotDate(date);
        row.setIdCardHash(hash);
        return row;
    }

    private static Reservation reservation(long rno, LocalDate date, String hash, int status) {
        Reservation row = new Reservation();
        row.setReservationNo(rno);
        row.setSlotDate(date);
        row.setIdCardHash(hash);
        row.setStatus(status);
        return row;
    }

    private static SlotBucket bucket(int number, int total, int occupied) {
        SlotBucket row = new SlotBucket();
        row.setBucketNo(number);
        row.setTotal(total);
        row.setOccupied(occupied);
        return row;
    }

    private static ReconcileService service(StuckReservationMapper stuck,
                                             RollbackService rollback) {
        return service(stuck, rollback, mock(ReservationMapper.class));
    }

    private static ReconcileService service(StuckReservationMapper stuck,
                                             RollbackService rollback,
                                             ReservationMapper reservations) {
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        StateLog rollbackClaim = new StateLog();
        rollbackClaim.setStatus(4);
        when(stateLogs.selectById("rx-" + RNO)).thenReturn(rollbackClaim);
        return service(stuck, rollback, reservations, stateLogs);
    }

    private static ReconcileService service(StuckReservationMapper stuck,
                                             RollbackService rollback,
                                             ReservationMapper reservations,
                                             StateLogMapper stateLogs) {
        return new ReconcileService(mock(SlotMapper.class), mock(SlotBucketMapper.class),
                reservations, mock(IdCardRouteMapper.class),
                mock(ReconcileLogMapper.class), stuck, stateLogs,
                mock(VerificationLogMapper.class),
                mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(IdGenerator.class), mock(TimeSupport.class),
                rollback, new ReserveXProperties());
    }

    private static ReconcileService service(StuckReservationMapper stuck,
                                             RollbackService rollback,
                                             ReservationMapper reservations,
                                             StateLogMapper stateLogs,
                                             IdCardRouteMapper routes,
                                             ReconcileLogMapper logs,
                                             TimeSupport time) {
        return new ReconcileService(mock(SlotMapper.class), mock(SlotBucketMapper.class),
                reservations, routes, logs, stuck, stateLogs,
                mock(VerificationLogMapper.class),
                mock(StringRedisTemplate.class, org.mockito.Mockito.RETURNS_DEEP_STUBS),
                mock(IdGenerator.class), time, rollback, new ReserveXProperties());
    }
}
