package com.reservex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reservex.common.BizException;
import com.reservex.common.ErrorCode;
import com.reservex.entity.Reservation;
import com.reservex.mapper.sharding.ReservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Administrator reservation collection query, isolated from reconciliation workers. */
@Service
@RequiredArgsConstructor
public class AdminReservationQueryService {

    private final ReservationMapper reservationMapper;

    public ReservationPage list(Long rno, LocalDate slotDate, String statusName,
                                String cursor, int size) {
        if (size < 1 || size > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分页大小必须在 1 到 500 之间");
        }
        LambdaQueryWrapper<Reservation> query = new LambdaQueryWrapper<>();
        if (rno != null) {
            query.eq(Reservation::getReservationNo, rno);
        }
        if (slotDate != null) {
            query.eq(Reservation::getSlotDate, slotDate);
        }
        if (statusName != null && !statusName.isBlank()) {
            query.eq(Reservation::getStatus, statusToCode(statusName));
        }
        Cursor decoded = decodeCursor(cursor);
        if (decoded != null) {
            query.and(w -> w.lt(Reservation::getCreateAt, decoded.createdAt())
                    .or(same -> same.eq(Reservation::getCreateAt, decoded.createdAt())
                            .lt(Reservation::getReservationNo, decoded.reservationNo())));
        }
        query.orderByDesc(Reservation::getCreateAt)
                .orderByDesc(Reservation::getReservationNo)
                .last("LIMIT " + (size + 1));

        List<Reservation> rows = reservationMapper.selectList(query);
        boolean hasMore = rows.size() > size;
        int visible = Math.min(rows.size(), size);
        List<ReservationView> result = new ArrayList<>(visible);
        for (Reservation row : rows.subList(0, visible)) {
            result.add(toView(row));
        }
        String nextCursor = hasMore && visible > 0 ? encodeCursor(rows.get(visible - 1)) : null;
        return new ReservationPage(result, hasMore, nextCursor);
    }

    private static String encodeCursor(Reservation row) {
        String raw = row.getCreateAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "|" + row.getReservationNo();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException();
            }
            LocalDateTime createdAt = LocalDateTime.parse(raw.substring(0, separator),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            long reservationNo = Long.parseLong(raw.substring(separator + 1));
            if (reservationNo <= 0) {
                throw new IllegalArgumentException();
            }
            return new Cursor(createdAt, reservationNo);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "分页游标无效");
        }
    }

    private static int statusToCode(String name) {
        return switch (name.toUpperCase()) {
            case "CONFIRMED" -> 0;
            case "VERIFIED" -> 1;
            case "CANCELLED" -> 2;
            case "EXPIRED" -> 3;
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    private static String codeToStatus(int code) {
        return switch (code) {
            case 0 -> "CONFIRMED";
            case 1 -> "VERIFIED";
            case 2 -> "CANCELLED";
            case 3 -> "EXPIRED";
            default -> "UNKNOWN";
        };
    }

    private static ReservationView toView(Reservation row) {
        return new ReservationView(row.getReservationNo(), row.getUserId(), row.getSlotId(),
                row.getSlotDate(), codeToStatus(row.getStatus()), row.getVersion(),
                row.getCreateAt(), row.getVerifiedAt(), row.getIdCardMasked());
    }

    public record ReservationView(Long reservationNo, Long userId, Long slotId,
                                  LocalDate slotDate, String status, Integer version,
                                  LocalDateTime createAt, LocalDateTime verifyTime,
                                  String idCardMasked) {
    }

    public record ReservationPage(List<ReservationView> items, boolean hasMore, String nextCursor) {
    }

    private record Cursor(LocalDateTime createdAt, long reservationNo) {
    }
}
