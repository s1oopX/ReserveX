package com.reservex.worker;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeoutExpireConsumerTest {

    private final ReservationMapper reservations = mock(ReservationMapper.class);
    private final ReservationTransitionOutboxMapper outboxes =
            mock(ReservationTransitionOutboxMapper.class);
    private final ReservationTransitionOutboxService publisher =
            mock(ReservationTransitionOutboxService.class);
    private final ConsumedEventMapper consumed = mock(ConsumedEventMapper.class);
    private final StateLogMapper stateLogs = mock(StateLogMapper.class);
    private final TimeSupport time = mock(TimeSupport.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private TimeoutExpireConsumer consumer;

    @BeforeEach
    void setUp() {
        when(consumed.existsBy("cg-persistence", "rc-10")).thenReturn(1);
        when(time.now()).thenReturn(LocalDateTime.of(2026, 8, 17, 18, 1));
        ReserveXProperties props = new ReserveXProperties();
        props.getConsumer().getGroups().put("persistence", "cg-persistence");
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        consumer = new TimeoutExpireConsumer(reservations, outboxes, publisher,
                consumed, stateLogs, time, props, txManager);
    }

    @Test
    void casExpiryWritesOutboxAndPublishes() {
        when(reservations.expireByNo(7L, 10L, LocalDateTime.of(2026, 8, 17, 18, 1)))
                .thenReturn(1);
        consumer.onMessage(new TimeoutExpireMessage("te-10", 10L, 7L,
                LocalDateTime.of(2026, 8, 17, 18, 0), "timeout-10"));

        verify(reservations).expireByNo(7L, 10L, LocalDateTime.of(2026, 8, 17, 18, 1));
        ArgumentCaptor<ReservationTransitionOutbox> captured =
                ArgumentCaptor.forClass(ReservationTransitionOutbox.class);
        verify(outboxes).insert(captured.capture());
        verify(publisher).tryPublish(captured.getValue());
        InOrder order = inOrder(txManager, publisher);
        order.verify(txManager).commit(any());
        order.verify(publisher).tryPublish(captured.getValue());
        assertThat(captured.getValue().getTransitionId()).isEqualTo("expired-10");
        assertThat(captured.getValue().getEventType()).isEqualTo("EXPIRED");
        assertThat(captured.getValue().getUserId()).isEqualTo(7L);
        assertThat(captured.getValue().getReservationNo()).isEqualTo(10L);
        assertThat(captured.getValue().getEventTime())
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 18, 0));
        assertThat(captured.getValue().getCreateAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 18, 1));
    }

    @Test
    void replayAfterExpiryDoesNotCreateAnotherOutbox() {
        when(reservations.expireByNo(7L, 10L, LocalDateTime.of(2026, 8, 17, 18, 1)))
                .thenReturn(0);

        consumer.onMessage(new TimeoutExpireMessage("te-10", 10L, 7L,
                LocalDateTime.of(2026, 8, 17, 18, 0), "timeout-10"));

        verify(outboxes, never()).insert(any(ReservationTransitionOutbox.class));
        verify(publisher, never()).tryPublish(any());
    }

    @Test
    void durableCancelIntentCannotBeOvertakenByTimeout() {
        StateLog cancelled = new StateLog();
        cancelled.setStatus(3);
        when(stateLogs.selectById("rx-10")).thenReturn(cancelled);

        consumer.onMessage(new TimeoutExpireMessage("te-10", 10L, 7L,
                LocalDateTime.of(2026, 8, 17, 18, 0), "timeout-10"));

        verify(reservations, never()).expireByNo(any(), any(), any());
        verify(outboxes, never()).insert(any(ReservationTransitionOutbox.class));
        verify(publisher, never()).tryPublish(any());
    }
}
