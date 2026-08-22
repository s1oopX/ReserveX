package com.reservex.service;

import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.AuditLog;
import com.reservex.entity.ReservationEvent;
import com.reservex.entity.StateLog;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationTransitionOutboxServiceTest {

    @Test
    void failedDeliveryStaysPendingAndRetryIsIdempotent() {
        ReservationTransitionOutboxMapper outboxes =
                mock(ReservationTransitionOutboxMapper.class);
        ReservationEventMapper events = mock(ReservationEventMapper.class);
        StateLogMapper states = mock(StateLogMapper.class);
        VerificationLogMapper verifications = mock(VerificationLogMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());
        doNothing().when(tx).rollback(any());
        when(events.insertIgnore(any()))
                .thenThrow(new IllegalStateException("single store down"))
                .thenReturn(1)
                .thenReturn(0);
        ReservationTransitionOutboxService service = new ReservationTransitionOutboxService(
                outboxes, events, states, verifications, audits, tx);
        ReservationTransitionOutbox outbox = verifiedOutbox();

        assertThat(service.tryPublish(outbox)).isFalse();
        verify(outboxes, never()).deletePending(any(), any());

        assertThat(service.tryPublish(outbox)).isTrue();
        assertThat(service.tryPublish(outbox)).isTrue();
        verify(states).confirm("rx-22");
        verify(verifications).insertSuccess(any());
        verify(audits).insert(any(AuditLog.class));
        verify(outboxes, times(2)).deletePending("verified-22", 11L);
    }

    @Test
    void expiredDeliveryWritesCancelStateAndStatusThreeEvent() {
        ReservationTransitionOutboxMapper outboxes =
                mock(ReservationTransitionOutboxMapper.class);
        ReservationEventMapper events = mock(ReservationEventMapper.class);
        StateLogMapper states = mock(StateLogMapper.class);
        VerificationLogMapper verifications = mock(VerificationLogMapper.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        PlatformTransactionManager tx = transactionManager();
        when(events.insertIgnore(any())).thenReturn(1);
        StateLog cancelled = new StateLog();
        cancelled.setStatus(3);
        when(states.selectById("rx-22")).thenReturn(cancelled);
        ReservationTransitionOutboxService service = new ReservationTransitionOutboxService(
                outboxes, events, states, verifications, audits, tx);
        ReservationTransitionOutbox outbox = expiredOutbox();

        assertThat(service.tryPublish(outbox)).isTrue();

        verify(states).insertOrCancel("rx-22", "22");
        ArgumentCaptor<ReservationEvent> captured =
                ArgumentCaptor.forClass(ReservationEvent.class);
        verify(events).insertIgnore(captured.capture());
        assertThat(captured.getValue().getEventType()).isEqualTo("EXPIRED");
        assertThat(captured.getValue().getToStatus()).isEqualTo(3);
        verify(outboxes).deletePending("expired-22", 11L);
    }

    @Test
    void rollbackClaimKeepsExpiredOutboxPending() {
        ReservationTransitionOutboxMapper outboxes =
                mock(ReservationTransitionOutboxMapper.class);
        ReservationEventMapper events = mock(ReservationEventMapper.class);
        StateLogMapper states = mock(StateLogMapper.class);
        when(events.insertIgnore(any())).thenReturn(0);
        StateLog rollbackClaim = new StateLog();
        rollbackClaim.setStatus(4);
        StateLog cancelled = new StateLog();
        cancelled.setStatus(3);
        when(states.selectById("rx-22")).thenReturn(rollbackClaim, cancelled);
        ReservationTransitionOutboxService service = new ReservationTransitionOutboxService(
                outboxes, events, states, mock(VerificationLogMapper.class),
                mock(AuditLogMapper.class), transactionManager());

        assertThat(service.tryPublish(expiredOutbox())).isFalse();
        verify(outboxes, never()).deletePending(any(), any());

        assertThat(service.tryPublish(expiredOutbox())).isTrue();
        verify(outboxes).deletePending("expired-22", 11L);
    }

    private static ReservationTransitionOutbox verifiedOutbox() {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("verified-22");
        outbox.setUserId(11L);
        outbox.setReservationNo(22L);
        outbox.setEventType("VERIFIED");
        outbox.setOperatorType("STAFF");
        outbox.setOperatorId(99L);
        outbox.setMethod(1);
        outbox.setManual(true);
        outbox.setVerificationId(100L);
        outbox.setAuditId(101L);
        outbox.setRequestId("request-1");
        outbox.setEventTime(LocalDateTime.of(2026, 8, 17, 12, 0));
        outbox.setCreateAt(outbox.getEventTime());
        return outbox;
    }

    private static ReservationTransitionOutbox expiredOutbox() {
        ReservationTransitionOutbox outbox = new ReservationTransitionOutbox();
        outbox.setTransitionId("expired-22");
        outbox.setUserId(11L);
        outbox.setReservationNo(22L);
        outbox.setEventType("EXPIRED");
        outbox.setOperatorType("SYSTEM");
        outbox.setManual(false);
        outbox.setRequestId("timeout-22");
        outbox.setEventTime(LocalDateTime.of(2026, 8, 17, 12, 0));
        outbox.setCreateAt(outbox.getEventTime());
        return outbox;
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        doNothing().when(tx).commit(any());
        doNothing().when(tx).rollback(any());
        return tx;
    }
}
