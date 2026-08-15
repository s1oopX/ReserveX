package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.config.ReserveXProperties;
import com.reservex.mapper.sharding.UserMapper;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.service.ReservationService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                mock(StuckReservationMapper.class), new ReserveXProperties(), time).scan();

        verify(zset).remove(ReservationService.PENDING_KEY, "2");
    }
}
