package com.reservex.service;

import com.reservex.entity.AuditLog;
import com.reservex.entity.ReservationEvent;
import com.reservex.entity.ReservationTransitionOutbox;
import com.reservex.entity.StateLog;
import com.reservex.entity.VerificationLog;
import com.reservex.mapper.sharding.ReservationTransitionOutboxMapper;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.ReservationEventMapper;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.mapper.single.VerificationLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Replays durable sharded transitions into the single-store audit trail. */
@Slf4j
@Service
public class ReservationTransitionOutboxService {

    private final ReservationTransitionOutboxMapper outboxMapper;
    private final ReservationEventMapper eventMapper;
    private final StateLogMapper stateLogMapper;
    private final VerificationLogMapper verificationMapper;
    private final AuditLogMapper auditMapper;
    private final TransactionTemplate singleTx;

    public ReservationTransitionOutboxService(
            ReservationTransitionOutboxMapper outboxMapper,
            ReservationEventMapper eventMapper,
            StateLogMapper stateLogMapper,
            VerificationLogMapper verificationMapper,
            AuditLogMapper auditMapper,
            @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.outboxMapper = outboxMapper;
        this.eventMapper = eventMapper;
        this.stateLogMapper = stateLogMapper;
        this.verificationMapper = verificationMapper;
        this.auditMapper = auditMapper;
        this.singleTx = new TransactionTemplate(singleTxManager);
    }

    @Scheduled(cron = "${reservex.reconcile.crons.transition-outbox:*/10 * * * * ?}")
    @Async("reconcileExecutor")
    public void drain() {
        outboxMapper.selectPending(200).forEach(this::tryPublish);
    }

    public boolean tryPublish(ReservationTransitionOutbox outbox) {
        try {
            singleTx.executeWithoutResult(status -> publishSingle(outbox));
            outboxMapper.deletePending(outbox.getTransitionId(), outbox.getUserId());
            return true;
        } catch (RuntimeException e) {
            log.warn("预约终态流水待重放 transitionId={}", outbox.getTransitionId(), e);
            return false;
        }
    }

    private void publishSingle(ReservationTransitionOutbox outbox) {
        int eventInserted = eventMapper.insertIgnore(event(outbox));
        if (eventInserted == 0 && !"EXPIRED".equals(outbox.getEventType())) {
            return;
        }
        String xid = "rx-" + outbox.getReservationNo();
        switch (outbox.getEventType()) {
            case "VERIFIED" -> {
                stateLogMapper.confirm(xid);
                verificationMapper.insertSuccess(verification(outbox));
                if (Boolean.TRUE.equals(outbox.getManual())) {
                    auditMapper.insert(manualAudit(outbox));
                }
            }
            case "CANCELLED" -> stateLogMapper.insertOrCancel(
                    xid, Long.toString(outbox.getReservationNo()));
            case "EXPIRED" -> {
                stateLogMapper.insertOrCancel(xid, Long.toString(outbox.getReservationNo()));
                StateLog state = stateLogMapper.selectById(xid);
                if (state == null || state.getStatus() != 3) {
                    throw new IllegalStateException(
                            "过期被回滚仲裁拦截 rno=" + outbox.getReservationNo());
                }
            }
            default -> throw new IllegalStateException(
                    "未知预约终态事件 " + outbox.getEventType());
        }
    }

    private static ReservationEvent event(ReservationTransitionOutbox outbox) {
        ReservationEvent event = new ReservationEvent();
        event.setEventId(outbox.getTransitionId());
        event.setReservationNo(outbox.getReservationNo());
        event.setEventType(outbox.getEventType());
        event.setFromStatus(0);
        event.setToStatus(switch (outbox.getEventType()) {
            case "VERIFIED" -> 1;
            case "CANCELLED" -> 2;
            case "EXPIRED" -> 3;
            default -> throw new IllegalStateException(
                    "未知预约终态事件 " + outbox.getEventType());
        });
        event.setOperatorType(outbox.getOperatorType());
        event.setOperatorId(outbox.getOperatorId());
        event.setRequestId(outbox.getRequestId());
        event.setEventTime(outbox.getEventTime());
        return event;
    }

    private static VerificationLog verification(ReservationTransitionOutbox outbox) {
        VerificationLog log = new VerificationLog();
        log.setVerifyId(outbox.getVerificationId());
        log.setReservationNo(outbox.getReservationNo());
        log.setStaffId(outbox.getOperatorId());
        log.setMethod(outbox.getMethod());
        log.setQrNonce(outbox.getQrNonce());
        log.setResult(0);
        log.setVerifyTime(outbox.getEventTime());
        return log;
    }

    private static AuditLog manualAudit(ReservationTransitionOutbox outbox) {
        AuditLog audit = new AuditLog();
        audit.setId(outbox.getAuditId());
        audit.setOperatorType("STAFF");
        audit.setOperatorId(outbox.getOperatorId());
        audit.setAction("MANUAL_VERIFY");
        audit.setTargetType("RESERVATION");
        audit.setTargetId(outbox.getReservationNo());
        audit.setAfter("{\"status\":\"VERIFIED\"}");
        audit.setRequestId(outbox.getRequestId());
        audit.setCreateAt(outbox.getEventTime());
        return audit;
    }
}
