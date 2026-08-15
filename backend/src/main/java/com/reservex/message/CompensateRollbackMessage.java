package com.reservex.message;

/** 只有持久配额冲突等“业务上确定不能成立”的预约才允许发此消息。 */
public record CompensateRollbackMessage(
        String eventId,
        long reservationNo,
        String dupKey,
        String bucketKey,
        String slotFullKey,
        String reason,
        String requestId) {
}
