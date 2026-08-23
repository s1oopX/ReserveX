package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.entity.DeadLetterMessage;
import com.reservex.entity.AuditLog;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DeadLetterService {

    private static final int MESSAGE_ID_MAX_LENGTH = 64;

    private static final Map<String, String> TARGETS = Map.of(
            "cg-persistence", "reservation-created",
            "cg-rollback", "compensate-rollback",
            "cg-timeout", "timeout");

    private final DeadLetterMessageMapper mapper;
    private final AuditLogMapper auditLogMapper;
    private final IdGenerator idGenerator;
    private final RocketMQTemplate rocketMQ;
    private final TimeSupport time;
    private final TransactionTemplate singleTx;

    @Autowired
    public DeadLetterService(DeadLetterMessageMapper mapper, AuditLogMapper auditLogMapper,
                             IdGenerator idGenerator, RocketMQTemplate rocketMQ,
                             TimeSupport time,
                             @Qualifier("singleTxManager") PlatformTransactionManager singleTxManager) {
        this.mapper = mapper;
        this.auditLogMapper = auditLogMapper;
        this.idGenerator = idGenerator;
        this.rocketMQ = rocketMQ;
        this.time = time;
        this.singleTx = singleTxManager == null ? null : new TransactionTemplate(singleTxManager);
    }

    /** Test-only compatibility constructor; production uses the qualified single-db transaction manager. */
    public DeadLetterService(DeadLetterMessageMapper mapper, AuditLogMapper auditLogMapper,
                             IdGenerator idGenerator, RocketMQTemplate rocketMQ,
                             TimeSupport time) {
        this(mapper, auditLogMapper, idGenerator, rocketMQ, time, null);
    }

    /** Legacy test constructor kept to avoid coupling unit tests to Spring transaction wiring. */
    public DeadLetterService(DeadLetterMessageMapper mapper, RocketMQTemplate rocketMQ,
                             TimeSupport time) {
        this(mapper, null, null, rocketMQ, time, null);
    }

    public void capture(String sourceGroup, MessageExt raw) {
        String target = TARGETS.get(sourceGroup);
        if (target == null) {
            throw new IllegalArgumentException("未知死信来源 group=" + sourceGroup);
        }
        DeadLetterMessage message = new DeadLetterMessage();
        message.setMessageId(raw.getMsgId());
        message.setSourceGroup(sourceGroup);
        message.setTargetTopic(target);
        message.setBody(new String(raw.getBody(), StandardCharsets.UTF_8));
        message.setReconsumeTimes(raw.getReconsumeTimes());
        message.setStatus(0);
        message.setCapturedAt(time.now());
        mapper.insertIgnore(message);
    }

    public List<View> list() {
        return mapper.selectRecent(100).stream().map(DeadLetterService::view).toList();
    }

    public View replay(String messageId, long resolverId) {
        String validMessageId = requireMessageId(messageId);
        DeadLetterMessage message = mapper.selectById(validMessageId);
        if (message == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (message.getStatus() == 1) {
            return view(message);
        }
        var now = time.now();
        if (mapper.claimReplay(validMessageId, now.minus(Duration.ofMinutes(5)), now, resolverId) != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "死信正在重放，请稍后刷新");
        }
        String target = TARGETS.get(message.getSourceGroup());
        if (target == null || !target.equals(message.getTargetTopic())) {
            mapper.releaseReplay(validMessageId);
            throw new BizException(ErrorCode.BAD_REQUEST, "死信目标不合法，拒绝重放");
        }
        try {
            rocketMQ.syncSend(target, message.getBody());
        } catch (RuntimeException e) {
            mapper.releaseReplay(validMessageId);
            throw new BizException(ErrorCode.SERVICE_DEGRADED, "死信重放失败");
        }
        completeReplay(message, resolverId);
        recordReplayAuditSafely(message, resolverId);
        return view(mapper.selectById(validMessageId));
    }

    private static String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank() || messageId.length() > MESSAGE_ID_MAX_LENGTH
                || messageId.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "死信消息标识不合法");
        }
        return messageId;
    }

    private void completeReplay(DeadLetterMessage message, long resolverId) {
        Runnable complete = () -> {
            if (mapper.completeReplay(message.getMessageId(), time.now(), resolverId) != 1) {
                throw new BizException(ErrorCode.SERVICE_DEGRADED, "死信已发送但状态未收口，请刷新核对");
            }
        };
        if (singleTx == null) {
            complete.run();
        } else {
            singleTx.executeWithoutResult(status -> complete.run());
        }
    }

    private void recordReplayAuditSafely(DeadLetterMessage message, long resolverId) {
        try {
            recordReplayAudit(message, resolverId);
        } catch (RuntimeException e) {
            log.error("死信已重放但审计写入失败 messageHash={} resolverId={}",
                    messageHash(message.getMessageId()), resolverId, e);
        }
    }

    private void recordReplayAudit(DeadLetterMessage message, long resolverId) {
        if (auditLogMapper == null || idGenerator == null) {
            return;
        }
        AuditLog audit = new AuditLog();
        audit.setId(idGenerator.nextId());
        audit.setOperatorType("ADMIN");
        audit.setOperatorId(resolverId);
        audit.setAction("DLQ_REINJECT");
        audit.setTargetType("DEAD_LETTER_MESSAGE");
        audit.setTargetId(null);
        String messageHash = messageHash(message.getMessageId());
        audit.setBefore("{\"status\":\"PENDING\",\"messageHash\":\""
                + messageHash + "\"}");
        audit.setAfter("{\"status\":\"REPLAYED\"}");
        audit.setRequestId("dlq-replay-" + messageHash);
        audit.setCreateAt(time.now());
        if (auditLogMapper.insert(audit) != 1) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED, "死信重放审计写入失败，请重试");
        }
    }

    private static String messageHash(String messageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(messageId.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    private static View view(DeadLetterMessage message) {
        return new View(message.getMessageId(), message.getSourceGroup(), message.getTargetTopic(),
                message.getReconsumeTimes(), switch (message.getStatus()) {
                    case 0 -> "PENDING";
                    case 1 -> "REPLAYED";
                    case 2 -> "REPLAYING";
                    default -> "UNKNOWN";
                }, message.getCapturedAt(), message.getUpdateAt(), message.getResolverId());
    }

    public record View(String messageId, String sourceGroup, String targetTopic,
                       int reconsumeTimes, String status,
                       java.time.LocalDateTime capturedAt, java.time.LocalDateTime updateAt,
                       Long resolverId) {
    }
}
