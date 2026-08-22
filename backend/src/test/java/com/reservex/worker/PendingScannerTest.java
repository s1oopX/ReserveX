package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.entity.User;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.ReservationCreatedMessage;
import com.reservex.lua.LuaScripts;
import com.reservex.service.ReservationService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
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
                mock(StuckReservationMapper.class), new ReserveXProperties(), time, mock(LuaScripts.class)).scan();

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
        new PendingScanner(redis, mock(RocketMQTemplate.class), users,
                mock(StuckReservationMapper.class), new ReserveXProperties(), time, mock(LuaScripts.class)).scan();

        verify(redis, times(2)).persist(occupyKey);
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
        new PendingScanner(redis, rocketMQ, users, mock(StuckReservationMapper.class),
                new ReserveXProperties(), time, lua).scan();

        verify(redis).persist(occupyKey);
        verify(redis, never()).expire(eq(occupyKey), any(Duration.class));
        verify(hash, never()).put(eq(occupyKey), eq("reinject_count"), any());
    }
}
