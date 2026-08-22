package com.reservex.service;

import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.common.TimeSupport;
import com.reservex.entity.DeadLetterMessage;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DeadLetterService {

    private static final int MESSAGE_ID_MAX_LENGTH = 64;

    private static final Map<String, String> TARGETS = Map.of(
            "cg-persistence", "reservation-created",
            "cg-rollback", "compensate-rollback",
            "cg-timeout", "timeout");

    private final DeadLetterMessageMapper mapper;
    private final RocketMQTemplate rocketMQ;
    private final TimeSupport time;

    public DeadLetterService(DeadLetterMessageMapper mapper, RocketMQTemplate rocketMQ,
                             TimeSupport time) {
        this.mapper = mapper;
        this.rocketMQ = rocketMQ;
        this.time = time;
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
        if (mapper.completeReplay(validMessageId, time.now(), resolverId) != 1) {
            throw new BizException(ErrorCode.SERVICE_DEGRADED, "死信已发送但状态未收口，请刷新核对");
        }
        return view(mapper.selectById(validMessageId));
    }

    private static String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank() || messageId.length() > MESSAGE_ID_MAX_LENGTH
                || messageId.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "死信消息标识不合法");
        }
        return messageId;
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
