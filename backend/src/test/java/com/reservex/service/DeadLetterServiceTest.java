package com.reservex.service;

import com.reservex.common.TimeSupport;
import com.reservex.entity.DeadLetterMessage;
import com.reservex.entity.AuditLog;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.id.IdGenerator;
import com.reservex.mapper.single.AuditLogMapper;
import com.reservex.mapper.single.DeadLetterMessageMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeadLetterServiceTest {

    @Test
    void capturePersistsTheRawDlqMessageWithAServerOwnedTarget() {
        DeadLetterMessageMapper mapper = mock(DeadLetterMessageMapper.class);
        TimeSupport time = mock(TimeSupport.class);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 12, 0);
        when(time.now()).thenReturn(now);
        MessageExt raw = new MessageExt();
        raw.setMsgId("msg-1");
        raw.setBody("{\"eventId\":\"rc-1\"}".getBytes(StandardCharsets.UTF_8));
        raw.setReconsumeTimes(16);

        new DeadLetterService(mapper, mock(RocketMQTemplate.class), time)
                .capture("cg-persistence", raw);

        ArgumentCaptor<DeadLetterMessage> captured = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(mapper).insertIgnore(captured.capture());
        assertEquals("reservation-created", captured.getValue().getTargetTopic());
        assertEquals("{\"eventId\":\"rc-1\"}", captured.getValue().getBody());
        assertEquals(0, captured.getValue().getStatus());
    }

    @Test
    void replayClaimsThenPublishesOnlyToTheMappedBusinessTopic() {
        DeadLetterMessageMapper mapper = mock(DeadLetterMessageMapper.class);
        RocketMQTemplate rocketMQ = mock(RocketMQTemplate.class);
        AuditLogMapper audits = mock(AuditLogMapper.class);
        IdGenerator ids = mock(IdGenerator.class);
        TimeSupport time = mock(TimeSupport.class);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 12, 0);
        when(time.now()).thenReturn(now);
        DeadLetterMessage pending = message(0);
        DeadLetterMessage replayed = message(1);
        when(mapper.selectById("msg-1")).thenReturn(pending, replayed);
        when(mapper.claimReplay(eq("msg-1"), any(), eq(now), eq(7L))).thenReturn(1);
        when(mapper.completeReplay("msg-1", now, 7L)).thenReturn(1);
        when(ids.nextId()).thenReturn(99L);
        when(audits.insert((AuditLog) any(AuditLog.class))).thenReturn(1);

        DeadLetterService.View result = new DeadLetterService(mapper, audits, ids, rocketMQ, time)
                .replay("msg-1", 7L);

        verify(rocketMQ).syncSend("reservation-created", pending.getBody());
        verify(audits).insert(org.mockito.ArgumentMatchers.<AuditLog>argThat(
                audit -> "DLQ_REINJECT".equals(audit.getAction())
                        && "DEAD_LETTER_MESSAGE".equals(audit.getTargetType())));
        assertEquals("REPLAYED", result.status());
    }

    @Test
    void replayRejectsOversizedOrControlCharacterMessageIdsBeforeQuerying() {
        DeadLetterMessageMapper mapper = mock(DeadLetterMessageMapper.class);
        DeadLetterService service = new DeadLetterService(mapper, mock(RocketMQTemplate.class),
                mock(TimeSupport.class));

        BizException oversized = assertThrows(BizException.class,
                () -> service.replay("x".repeat(65), 7L));
        assertEquals(ErrorCode.BAD_REQUEST, oversized.getErrorCode());

        BizException control = assertThrows(BizException.class,
                () -> service.replay("msg-1\n", 7L));
        assertEquals(ErrorCode.BAD_REQUEST, control.getErrorCode());
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    private static DeadLetterMessage message(int status) {
        DeadLetterMessage message = new DeadLetterMessage();
        message.setMessageId("msg-1");
        message.setSourceGroup("cg-persistence");
        message.setTargetTopic("reservation-created");
        message.setBody("{\"eventId\":\"rc-1\"}");
        message.setReconsumeTimes(16);
        message.setStatus(status);
        message.setCapturedAt(LocalDateTime.of(2026, 8, 18, 11, 0));
        return message;
    }
}
