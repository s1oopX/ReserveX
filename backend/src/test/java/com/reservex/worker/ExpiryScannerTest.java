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

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

        TimeSupport time = mock(TimeSupport.class);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 0, 5));
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ExpiryScanner scanner = new ExpiryScanner(
                reservationMapper,
                mock(ReconcileLogMapper.class),
                rocketMQ,
                mock(IdGenerator.class),
                time,
                txManager);

        scanner.scan();

        verify(rocketMQ).syncSend("timeout", new TimeoutExpireMessage(
                "te-10", 10L, 7L, LocalDateTime.of(2026, 8, 16, 18, 0), "timeout-10"));
    }
}
