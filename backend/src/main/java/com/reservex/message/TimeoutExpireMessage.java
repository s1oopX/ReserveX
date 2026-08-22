package com.reservex.message;

import java.time.LocalDateTime;

/** 到期预约逐条收口消息。eventId 固定为 te-{rno},重复投递安全。 */
public record TimeoutExpireMessage(
        String eventId,
        long reservationNo,
        long userId,
        LocalDateTime validUntil,
        String requestId) {
}
