package com.reservex.message;

/** 抢号成功后的可靠落库消息；全部时间用 epoch，避免消息转换器时区漂移。 */
public record ReservationCreatedMessage(
        String eventId,
        long reservationNo,
        long userId,
        long slotId,
        String slotDate,
        int slotHour,
        int bucketNo,
        String idCardHash,
        String idCardMasked,
        long validUntilEpoch,
        long createEpochMillis,
        String requestId,
        String dupKey,
        String bucketKey,
        String slotFullKey) {
}
