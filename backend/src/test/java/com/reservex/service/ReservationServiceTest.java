package com.reservex.service;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.session.SaSession;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.HttpPreconditions;
import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.Reservation;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.StuckReservation;
import com.reservex.id.IdGenerator;
import com.reservex.lua.LuaScripts;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.ReservationCreatedMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationServiceTest {

    @Test
    void malformedOwnedOccupyDoesNotBreakTheListAndDegradesDetail() {
        long userId = 11L;
        long rno = 22L;
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectList(any())).thenReturn(List.of());
        when(reservations.selectOne(any())).thenReturn(null);
        StuckReservationMapper stuckRows = mock(StuckReservationMapper.class);
        when(stuckRows.selectByUser(userId)).thenReturn(List.of());
        when(stuckRows.selectById(rno)).thenReturn(null);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hashes);
        when(zset.range(ReservationService.PENDING_KEY, 0, 499))
                .thenReturn(Set.of(Long.toString(rno)));
        when(hashes.entries(ReservationService.occupyKey(rno))).thenReturn(Map.of(
                "user_id", Long.toString(userId), "slot_id", "broken",
                "slot_date", "2026-08-19", "slot_hour", "9", "create_ts", "1000"));
        TimeSupport time = mock(TimeSupport.class);
        when(time.zone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReservationService service = new ReservationService(
                mock(IdGenerator.class), time, new ReserveXProperties(), mock(LuaScripts.class),
                redis, mock(RocketMQTemplate.class), mock(StpLogic.class), mock(SlotService.class),
                reservations, mock(ReservationTransitionOutboxMapper.class),
                mock(ReservationTransitionOutboxService.class), mock(StateLogMapper.class),
                mock(CaptchaService.class), stuckRows, tx);

        assertEquals(List.of(), service.mine(userId));
        BizException error = assertThrows(BizException.class, () -> service.detail(userId, rno));
        assertEquals(ErrorCode.SERVICE_DEGRADED, error.getErrorCode());
    }

    @Test
    void stuckReservationStaysVisibleAfterPendingIndexCleanupAndRollback() {
        long userId = 11L;
        long rno = 22L;
        long slotId = 33L;
        LocalDate slotDate = LocalDate.of(2026, 8, 19);
        LocalDateTime createdAt = slotDate.atTime(9, 0);
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectList(any())).thenReturn(List.of());
        when(reservations.selectOne(any())).thenReturn(null);
        StuckReservation stuck = new StuckReservation();
        stuck.setReservationNo(rno);
        stuck.setUserId(userId);
        stuck.setSlotId(slotId);
        stuck.setSlotDate(slotDate);
        stuck.setStatus(0);
        stuck.setCreateAt(createdAt);
        StuckReservationMapper stuckRows = mock(StuckReservationMapper.class);
        when(stuckRows.selectByUser(userId)).thenReturn(List.of(stuck));
        when(stuckRows.selectById(rno)).thenReturn(stuck);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hashes);
        when(zset.range(ReservationService.PENDING_KEY, 0, 499)).thenReturn(Set.of());
        when(hashes.entries(ReservationService.occupyKey(rno))).thenReturn(Map.of(
                "user_id", Long.toString(userId), "cancelled", "1"));
        SlotService slots = mock(SlotService.class);
        when(slots.getSlot(slotId)).thenReturn(new SlotService.SlotView(slotId, slotDate,
                9, 120, true, 0, slotDate.atTime(11, 0), 0, false));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        ReservationService service = new ReservationService(
                mock(IdGenerator.class), mock(TimeSupport.class), new ReserveXProperties(),
                mock(LuaScripts.class), redis, mock(RocketMQTemplate.class), mock(StpLogic.class),
                slots, reservations, mock(ReservationTransitionOutboxMapper.class),
                mock(ReservationTransitionOutboxService.class), mock(StateLogMapper.class),
                mock(CaptchaService.class), stuckRows, tx);

        assertEquals("REVIEW_REQUIRED", service.mine(userId).getFirst().status());
        assertEquals("REVIEW_REQUIRED", service.detail(userId, rno).status());
        stuck.setStatus(2);
        assertEquals("FAILED", service.detail(userId, rno).status());
    }

    @Test
    void cancelRejectsOwnedStuckReservationBeforeLua() {
        long userId = 11L;
        long rno = 22L;
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null);
        StuckReservation stuck = new StuckReservation();
        stuck.setReservationNo(rno);
        stuck.setUserId(userId);
        StuckReservationMapper stuckRows = mock(StuckReservationMapper.class);
        when(stuckRows.selectById(rno)).thenReturn(stuck);
        LuaScripts lua = mock(LuaScripts.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        ReservationService service = new ReservationService(
                mock(IdGenerator.class), mock(TimeSupport.class), new ReserveXProperties(), lua,
                mock(StringRedisTemplate.class), mock(RocketMQTemplate.class), mock(StpLogic.class),
                mock(SlotService.class), reservations,
                mock(ReservationTransitionOutboxMapper.class),
                mock(ReservationTransitionOutboxService.class), mock(StateLogMapper.class),
                mock(CaptchaService.class), stuckRows, tx);

        BizException error = assertThrows(BizException.class,
                () -> service.cancel(userId, rno, anyVersion()));

        assertEquals(ErrorCode.STATE_CONFLICT, error.getErrorCode());
        verify(lua, never()).evalLong(eq(LuaScripts.Script.MARK_CANCEL),
                anyList(), any(Object[].class));
    }

    @Test
    void duplicateGrabReturnsOriginalOnlyToSameUserAndSlot() {
        long userId = 11L;
        long slotId = 33L;
        long originalRno = 2_088_867_688_708_452_353L;
        String idHash = "id-card-hash";
        LocalDate slotDate = LocalDate.of(2026, 8, 17);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(hashes.entries("slot:meta:" + slotId)).thenReturn(Map.of(
                "released", "1", "valid_until", "4102444800", "slot_date", slotDate.toString(),
                "slot_hour", "14", "bucket_count", "2"));
        when(hashes.entries(ReservationService.occupyKey(originalRno)))
                .thenReturn(Map.of("user_id", Long.toString(userId), "slot_id", Long.toString(slotId)))
                .thenReturn(Map.of("user_id", Long.toString(userId), "slot_id", "44"))
                .thenReturn(Map.of("user_id", "99", "slot_id", Long.toString(slotId)))
                .thenReturn(Map.of("user_id", Long.toString(userId), "slot_id", Long.toString(slotId),
                        "rollback_pending", "1"))
                .thenReturn(Map.of());
        when(values.get("dup:" + slotDate + ":" + idHash)).thenReturn(Long.toString(originalRno));

        Reservation persisted = new Reservation();
        persisted.setReservationNo(originalRno);
        persisted.setUserId(userId);
        persisted.setSlotId(slotId);
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(persisted);

        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.GRAB), anyList(), any(Object[].class)))
                .thenReturn(-1L);
        StpLogic stpLogic = mock(StpLogic.class);
        SaSession session = mock(SaSession.class);
        when(stpLogic.getTokenSession()).thenReturn(session);
        when(session.get("idCardHash")).thenReturn(idHash);
        when(session.get("idCardMasked")).thenReturn("310***********1234");
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 12, 0));
        when(time.zone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(time.ttlUntilEndOfDay(slotDate, 7)).thenReturn(43_200L);
        CaptchaService captcha = mock(CaptchaService.class);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        ReservationService service = service(time, redis, lua, stpLogic,
                reservations, captcha, rocketMQ);

        assertEquals(originalRno, service.grab(userId, slotId, null).reservationNo());
        for (int i = 0; i < 3; i++) {
            BizException conflict = assertThrows(BizException.class,
                    () -> service.grab(userId, slotId, null));
            assertEquals(ErrorCode.QUOTA_USED, conflict.getErrorCode());
        }
        assertEquals(originalRno, service.grab(userId, slotId, null).reservationNo());
        verify(captcha, never()).clearRiskOnSuccess(userId);
        verify(rocketMQ, never()).syncSend(any(String.class), any(ReservationCreatedMessage.class));
    }

    @Test
    void cancelClosesConsumerCleanupRaceByCancellingNewlyPersistedRow() {
        long userId = 11L;
        long rno = 22L;
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);
        Reservation persisted = new Reservation();
        persisted.setReservationNo(rno);
        persisted.setUserId(userId);
        persisted.setStatus(0);
        persisted.setVersion(0);

        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null, persisted);
        when(reservations.selectById(rno)).thenReturn(null);
        when(reservations.cancelByNo(userId, rno, 0, now)).thenReturn(1);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.MARK_CANCEL), anyList(), any(Object[].class)))
                .thenReturn(1L);

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(now);
        when(time.toEpochSecond(now)).thenReturn(1_786_852_800L);
        StateLogMapper stateLogs = mock(StateLogMapper.class);
        ReservationTransitionOutboxMapper outbox = mock(ReservationTransitionOutboxMapper.class);
        ReservationTransitionOutboxService publisher = mock(ReservationTransitionOutboxService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());

        ReservationService service = new ReservationService(
                mock(IdGenerator.class), time, new ReserveXProperties(), lua,
                redis, mock(RocketMQTemplate.class), mock(StpLogic.class), mock(SlotService.class),
                reservations, outbox, publisher, stateLogs, mock(CaptchaService.class),
                mock(StuckReservationMapper.class), tx);

        assertDoesNotThrow(() -> service.cancel(userId, rno, anyVersion()));

        verify(lua).evalLong(LuaScripts.Script.MARK_CANCEL,
                java.util.List.of(ReservationService.occupyKey(rno)),
                Long.toString(userId), "cancelled-" + rno, "1786852800");
        verify(reservations).cancelByNo(userId, rno, 0, now);
        verify(stateLogs).insertOrCancel("rx-" + rno, Long.toString(rno));
        verify(outbox).insert(any(ReservationTransitionOutbox.class));
        verify(publisher).tryPublish(any());
    }

    @Test
    void cancelRechecksDatabaseWhenConsumerAlreadyDeletedOccupy() {
        long userId = 11L;
        long rno = 22L;
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);
        Reservation persisted = new Reservation();
        persisted.setReservationNo(rno);
        persisted.setUserId(userId);
        persisted.setStatus(0);
        persisted.setVersion(0);

        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null, persisted);
        when(reservations.selectById(rno)).thenReturn(null);
        when(reservations.cancelByNo(userId, rno, 0, now)).thenReturn(1);
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.MARK_CANCEL), anyList(), any(Object[].class)))
                .thenReturn(0L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(now);
        ReservationTransitionOutboxMapper outbox = mock(ReservationTransitionOutboxMapper.class);
        ReservationTransitionOutboxService publisher = mock(ReservationTransitionOutboxService.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());

        ReservationService service = new ReservationService(
                mock(IdGenerator.class), time, new ReserveXProperties(), lua,
                mock(StringRedisTemplate.class), mock(RocketMQTemplate.class),
                mock(StpLogic.class), mock(SlotService.class), reservations, outbox, publisher,
                mock(StateLogMapper.class), mock(CaptchaService.class),
                mock(StuckReservationMapper.class), tx);

        assertDoesNotThrow(() -> service.cancel(userId, rno, anyVersion()));

        verify(reservations).cancelByNo(userId, rno, 0, now);
        verify(outbox).insert(any(ReservationTransitionOutbox.class));
        verify(publisher).tryPublish(any());
    }

    @Test
    void cancelAcceptsConsumerApplyingTheSameCancellation() {
        long userId = 11L;
        long rno = 22L;
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);
        Reservation active = new Reservation();
        active.setReservationNo(rno);
        active.setUserId(userId);
        active.setStatus(0);
        active.setVersion(0);
        Reservation cancelled = new Reservation();
        cancelled.setReservationNo(rno);
        cancelled.setUserId(userId);
        cancelled.setStatus(2);
        cancelled.setVersion(1);

        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null, active, cancelled);
        when(reservations.selectById(rno)).thenReturn(null);
        when(reservations.cancelByNo(userId, rno, 0, now)).thenReturn(0);
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.MARK_CANCEL), anyList(), any(Object[].class)))
                .thenReturn(1L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(now);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());

        ReservationService service = new ReservationService(
                mock(IdGenerator.class), time, new ReserveXProperties(), lua,
                mock(StringRedisTemplate.class), mock(RocketMQTemplate.class),
                mock(StpLogic.class), mock(SlotService.class), reservations,
                mock(ReservationTransitionOutboxMapper.class),
                mock(ReservationTransitionOutboxService.class), mock(StateLogMapper.class),
                mock(CaptchaService.class), mock(StuckReservationMapper.class), tx);

        assertDoesNotThrow(() -> service.cancel(userId, rno, anyVersion()));
    }

    @Test
    void cancelRejectsExpiredInflightReservation() {
        long userId = 11L;
        long rno = 22L;
        LocalDateTime now = LocalDateTime.of(2026, 8, 16, 12, 0);
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null);
        when(reservations.selectById(rno)).thenReturn(null);
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.MARK_CANCEL), anyList(), any(Object[].class)))
                .thenReturn(2L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(now);

        ReservationService service = service(time, mock(StringRedisTemplate.class), lua,
                mock(StpLogic.class), reservations, mock(CaptchaService.class),
                mock(RocketMQTemplate.class));

        BizException error = assertThrows(BizException.class,
                () -> service.cancel(userId, rno, anyVersion()));
        assertEquals(ErrorCode.ALREADY_EXPIRED, error.getErrorCode());
    }

    @Test
    void cancelDoesNotRevealForeignInflightReservation() {
        long rno = 22L;
        ReservationMapper reservations = mock(ReservationMapper.class);
        when(reservations.selectOne(any())).thenReturn(null);
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.MARK_CANCEL), anyList(), any(Object[].class)))
                .thenReturn(-1L);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 16, 12, 0));
        ReservationService service = service(time, mock(StringRedisTemplate.class), lua,
                mock(StpLogic.class), reservations, mock(CaptchaService.class),
                mock(RocketMQTemplate.class));

        BizException error = assertThrows(BizException.class,
                () -> service.cancel(11L, rno, anyVersion()));

        assertEquals(ErrorCode.RESERVATION_NOT_FOUND, error.getErrorCode());
        verify(reservations, never()).selectById(rno);
    }

    private static ReservationService service(TimeSupport time, StringRedisTemplate redis,
                                              LuaScripts lua, StpLogic stpLogic,
                                              ReservationMapper reservations,
                                              CaptchaService captcha,
                                              RocketMQTemplate rocketMQ) {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());
        return new ReservationService(
                mock(IdGenerator.class), time, new ReserveXProperties(), lua, redis, rocketMQ,
                stpLogic, mock(SlotService.class), reservations,
                mock(ReservationTransitionOutboxMapper.class),
                mock(ReservationTransitionOutboxService.class), mock(StateLogMapper.class),
                captcha, mock(StuckReservationMapper.class), tx);
    }

    private static HttpPreconditions.VersionCondition anyVersion() {
        return HttpPreconditions.requireVersion("*");
    }
}
