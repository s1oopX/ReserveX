package com.reservex.worker;

import com.reservex.common.TimeSupport;
import com.reservex.entity.Reservation;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.sharding.ReservationMapper;
import com.reservex.mapper.single.ReconcileLogMapper;
import com.reservex.message.TimeoutExpireMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpiryScannerTest {

    @Test
    void catchesReservationsFromPreviousDaysAfterRestart() {
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        Reservation candidate = new Reservation();
        candidate.setReservationNo(10L);
        candidate.setUserId(7L);
        candidate.setStatus(0);
        candidate.setValidUntil(LocalDateTime.of(2026, 8, 16, 18, 0));
        when(reservationMapper.selectExpiryCandidates(LocalDateTime.of(2026, 8, 17, 0, 5), 500))
                .thenReturn(java.util.List.of(candidate));
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = redisAllowingPublishes();

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 0, 5));
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ExpiryScanner scanner = new ExpiryScanner(
                reservationMapper,
                mock(ReconcileLogMapper.class),
                rocketMQ,
                redis,
                mock(IdGenerator.class),
                time,
                txManager);

        scanner.scan();

        verify(rocketMQ).syncSend("timeout", new TimeoutExpireMessage(
                "te-10", 10L, 7L, LocalDateTime.of(2026, 8, 16, 18, 0), "timeout-10"));
    }

    @Test
    void publishGuardSuppressesTheSameCandidateForFiveMinutes() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        Reservation candidate = candidate(10L);
        when(reservations.selectExpiryCandidates(any(), eq(500)))
                .thenReturn(java.util.List.of(candidate));
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("timeout:publishing:10", "1", Duration.ofMinutes(5)))
                .thenReturn(true, false);

        ExpiryScanner scanner = scanner(reservations, rocketMQ, redis);
        scanner.scan();
        scanner.scan();

        verify(rocketMQ, times(1)).syncSend(eq("timeout"), any(TimeoutExpireMessage.class));
    }

    @Test
    void onePublishFailureDoesNotBlockFollowingCandidates() {
        ReservationMapper reservations = mock(ReservationMapper.class);
        Reservation first = candidate(10L);
        Reservation second = candidate(11L);
        when(reservations.selectExpiryCandidates(any(), eq(500)))
                .thenReturn(java.util.List.of(first, second));
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        TimeoutExpireMessage failed = message(first);
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQ).syncSend("timeout", failed);
        StringRedisTemplate redis = redisAllowingPublishes();

        scanner(reservations, rocketMQ, redis).scan();

        verify(redis).delete("timeout:publishing:10");
        verify(rocketMQ).syncSend("timeout", message(second));
    }

    private static ExpiryScanner scanner(ReservationMapper reservations,
                                         RocketMQTemplate rocketMQ,
                                         StringRedisTemplate redis) {
        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 0, 5));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new ExpiryScanner(reservations, mock(ReconcileLogMapper.class), rocketMQ,
                redis, mock(IdGenerator.class), time, tx);
    }

    private static StringRedisTemplate redisAllowingPublishes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), eq("1"), eq(Duration.ofMinutes(5))))
                .thenReturn(true);
        return redis;
    }

    private static Reservation candidate(long reservationNo) {
        Reservation candidate = new Reservation();
        candidate.setReservationNo(reservationNo);
        candidate.setUserId(7L);
        candidate.setValidUntil(LocalDateTime.of(2026, 8, 16, 18, 0));
        return candidate;
    }

    private static TimeoutExpireMessage message(Reservation candidate) {
        long rno = candidate.getReservationNo();
        return new TimeoutExpireMessage("te-" + rno, rno, candidate.getUserId(),
                candidate.getValidUntil(), "timeout-" + rno);
    }
}
