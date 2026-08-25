package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.User;
import com.reservex.entity.StuckReservation;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.ReservationCreatedMessage;
import com.reservex.lua.LuaScripts;
import com.reservex.metrics.ReserveXMetrics;
import com.reservex.service.ReservationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class PendingScannerTest {

    @Test
    void missingUserStillPersistsAValidRollbackHashFromOccupy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        when(zset.rangeByScore(ReservationService.PENDING_KEY, 0, 970_000, 0, 500))
                .thenReturn(new LinkedHashSet<>(List.of("10")));
        String occupyKey = ReservationService.occupyKey(10L);
        String idCardHash = "0".repeat(64);
        when(hash.entries(occupyKey)).thenReturn(Map.of(
                "user_id", "7", "slot_id", "99", "slot_date", "2026-08-16",
                "bucket", "slot:99:b:2", "reinject_count", "0"));
        when(redis.persist(occupyKey)).thenReturn(true);
        StuckReservationMapper stuck = mock(StuckReservationMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(1970, 1, 1, 0, 16, 40));
        when(time.zone()).thenReturn(ZoneId.of("UTC"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PendingScanner(redis, mock(RocketMQTemplate.class), mock(UserMapper.class),
                stuck, new ReserveXProperties(), time, mock(LuaScripts.class),
                new ReserveXMetrics(registry)).scan();

        ArgumentCaptor<StuckReservation> captured = ArgumentCaptor.forClass(StuckReservation.class);
        verify(stuck).insertIgnore(captured.capture());
        org.junit.jupiter.api.Assertions.assertEquals(idCardHash, captured.getValue().getIdCardHash());
        org.junit.jupiter.api.Assertions.assertEquals(
                "dup:2026-08-16:" + idCardHash, captured.getValue().getDupKey());
        // 只落表不报数就是静默:告警要靠这个计数器 + stuck.pending gauge。
        org.junit.jupiter.api.Assertions.assertEquals(1d, registry
                .get(ReserveXMetrics.STUCK_INTAKE)
                .tag("reason", ReserveXMetrics.REASON_USER_MISSING)
                .counter().count());
    }

    @Test
    void malformedOccupyDoesNotAbortFollowingEntries() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        when(zset.rangeByScore(ReservationService.PENDING_KEY, 0, 970_000, 0, 500))
                .thenReturn(new LinkedHashSet<>(List.of("1", "2")));
        when(hash.entries(ReservationService.occupyKey(1L)))
                .thenReturn(Map.of("user_id", "broken"));
        when(hash.entries(ReservationService.occupyKey(2L))).thenReturn(Map.of());

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(1970, 1, 1, 0, 16, 40));
        when(time.zone()).thenReturn(ZoneId.of("UTC"));
        new PendingScanner(redis, mock(RocketMQTemplate.class), mock(UserMapper.class),
                mock(StuckReservationMapper.class), new ReserveXProperties(), time,
                mock(LuaScripts.class), ReserveXMetrics.noop()).scan();

        verify(redis).persist(ReservationService.occupyKey(1L));
        verify(zset, never()).remove(ReservationService.PENDING_KEY, "1");
        verify(zset).remove(ReservationService.PENDING_KEY, "2");
    }

    @Test
    void stuckOccupyIsKeptWithoutRefreshingItsTtl() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        when(zset.rangeByScore(ReservationService.PENDING_KEY, 0, 970_000, 0, 500))
                .thenReturn(new LinkedHashSet<>(List.of("10")));
        String occupyKey = ReservationService.occupyKey(10L);
        when(hash.entries(occupyKey)).thenReturn(Map.of(
                "user_id", "7", "slot_id", "99", "slot_date", "2026-08-16",
                "bucket", "slot:bucket:99:2", "reinject_count", "5"));
        when(redis.persist(occupyKey)).thenReturn(true);
        User user = new User();
        user.setUserId(7L);
        user.setIdCardHash("id-card-hash");
        com.reservex.mapper.sharding.UserMapper users = mock(com.reservex.mapper.sharding.UserMapper.class);
        when(users.selectById(7L)).thenReturn(user);

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(1970, 1, 1, 0, 16, 40));
        when(time.zone()).thenReturn(ZoneId.of("UTC"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PendingScanner(redis, mock(RocketMQTemplate.class), users,
                mock(StuckReservationMapper.class), new ReserveXProperties(), time,
                mock(LuaScripts.class), new ReserveXMetrics(registry)).scan();

        verify(redis, times(2)).persist(occupyKey);
        // reinject_count=5 撞上限 → 转卡单,tag 必须是耗尽而不是用户缺失。
        org.junit.jupiter.api.Assertions.assertEquals(1d, registry
                .get(ReserveXMetrics.STUCK_INTAKE)
                .tag("reason", ReserveXMetrics.REASON_REINJECT_EXHAUSTED)
                .counter().count());
        verify(redis, never()).expire(eq(occupyKey), any(Duration.class));
    }

    @Test
    void brokerFailureKeepsOccupyUntilAReplayCanSucceed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        when(zset.rangeByScore(ReservationService.PENDING_KEY, 0, 970_000, 0, 500))
                .thenReturn(new LinkedHashSet<>(List.of("10")));
        String occupyKey = ReservationService.occupyKey(10L);
        when(hash.entries(occupyKey)).thenReturn(Map.of(
                "user_id", "7", "slot_id", "99", "slot_date", "2026-08-16",
                "slot_hour", "9", "bucket_no", "2", "bucket", "slot:bucket:99:2",
                "id_card_masked", "1101**********2X", "valid_until", "1000",
                "create_ts", "900", "reinject_count", "0"));
        User user = new User();
        user.setUserId(7L);
        user.setIdCardHash("id-card-hash");
        UserMapper users = mock(UserMapper.class);
        when(users.selectById(7L)).thenReturn(user);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQ).syncSend(eq("reservation-created"),
                        any(ReservationCreatedMessage.class));

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(1970, 1, 1, 0, 16, 40));
        when(time.zone()).thenReturn(ZoneId.of("UTC"));
        LuaScripts lua = mock(LuaScripts.class);
        when(lua.evalLong(eq(LuaScripts.Script.REINJECT), any(), any(Object[].class))).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PendingScanner(redis, rocketMQ, users, mock(StuckReservationMapper.class),
                new ReserveXProperties(), time, lua, new ReserveXMetrics(registry)).scan();

        verify(redis).persist(occupyKey);
        verify(redis, never()).expire(eq(occupyKey), any(Duration.class));
        verify(hash, never()).put(eq(occupyKey), eq("reinject_count"), any());
        // broker 挂了只写日志的话,「补投一直失败」在外部看不出来。
        org.junit.jupiter.api.Assertions.assertEquals(1d, registry
                .get(ReserveXMetrics.REINJECT_TOTAL)
                .tag("outcome", "failed")
                .counter().count());
    }
}
