package com.reservex.service;

import com.reservex.lua.LuaScripts;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.message.CompensateRollbackMessage;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RollbackService {

    private static final Pattern BUCKET_KEY = Pattern.compile("slot:([1-9]\\d*):b:(0|[1-9]\\d*)");
    private static final Pattern DUP_KEY = Pattern.compile(
            "dup:(\\d{4}-\\d{2}-\\d{2}):[0-9a-f]{64}");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final LuaScripts lua;
    private final StateLogMapper stateLogMapper;

    public RollbackService(LuaScripts lua, StateLogMapper stateLogMapper) {
        this.lua = lua;
        this.stateLogMapper = stateLogMapper;
    }

    public static String doneKey(long reservationNo) {
        return "rollback:done:" + reservationNo;
    }

    /** Executes the idempotent rollback and reports whether this call reclaimed stock. */
    public boolean compensate(CompensateRollbackMessage message) {
        validate(message);
        Long result = lua.evalLong(LuaScripts.Script.COMPENSATE, List.of(message.bucketKey()),
                Long.toString(message.reservationNo()), message.dupKey(),
                ReservationService.PENDING_KEY, message.slotFullKey());
        boolean compensated = Long.valueOf(1L).equals(result) || Long.valueOf(2L).equals(result);
        String xid = "rx-" + message.reservationNo();
        if (compensated && stateLogMapper.completeRollback(xid) == 0) {
            stateLogMapper.insertOrCancel(xid, Long.toString(message.reservationNo()));
        }
        return compensated;
    }

    private static void validate(CompensateRollbackMessage message) {
        if (message == null || message.reservationNo() <= 0) {
            throw invalid(message);
        }

        String expectedEventId;
        if ("ID_CARD_ROUTE_CONFLICT".equals(message.reason())) {
            expectedEventId = "cr-" + message.reservationNo();
        } else if ("MANUAL_ROLLBACK".equals(message.reason())) {
            expectedEventId = "manual-rollback-" + message.reservationNo();
        } else {
            throw invalid(message);
        }
        if (!expectedEventId.equals(message.eventId())
                || message.requestId() == null
                || !REQUEST_ID.matcher(message.requestId()).matches()) {
            throw invalid(message);
        }

        Matcher bucket = message.bucketKey() == null ? null : BUCKET_KEY.matcher(message.bucketKey());
        Matcher dup = message.dupKey() == null ? null : DUP_KEY.matcher(message.dupKey());
        if (bucket == null || !bucket.matches() || dup == null || !dup.matches()) {
            throw invalid(message);
        }
        try {
            long slotId = Long.parseLong(bucket.group(1));
            int bucketNo = Integer.parseInt(bucket.group(2));
            LocalDate.parse(dup.group(1));
            if (!ReservationService.bucketKey(slotId, bucketNo).equals(message.bucketKey())
                    || !("slot:full:" + slotId).equals(message.slotFullKey())) {
                throw invalid(message);
            }
        } catch (NumberFormatException | DateTimeParseException e) {
            throw invalid(message);
        }
    }

    private static IllegalArgumentException invalid(CompensateRollbackMessage message) {
        String reservationNo = message == null ? "null" : Long.toString(message.reservationNo());
        return new IllegalArgumentException("非法回滚消息 rno=" + reservationNo);
    }
}
