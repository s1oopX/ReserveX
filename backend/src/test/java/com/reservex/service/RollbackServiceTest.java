package com.reservex.service;

import com.reservex.lua.LuaScripts;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.message.CompensateRollbackMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RollbackServiceTest {

    @Test
    void reportsWhetherLuaActuallyReclaimedStock() {
        LuaScripts lua = mock(LuaScripts.class);
        CompensateRollbackMessage message = validMessage();
        when(lua.evalLong(LuaScripts.Script.COMPENSATE, List.of(message.bucketKey()),
                "10", message.dupKey(), ReservationService.PENDING_KEY, message.slotFullKey()))
                .thenReturn(1L, 2L, 0L);
        StateLogMapper stateLog = mock(StateLogMapper.class);
        RollbackService service = new RollbackService(lua, stateLog);

        assertTrue(service.compensate(message));
        assertTrue(service.compensate(message));
        assertFalse(service.compensate(message));
        verify(stateLog, times(2)).completeRollback("rx-10");
        verify(stateLog, times(2)).insertOrCancel("rx-10", "10");
    }

    @Test
    void rejectsSlotFullKeyForAnotherSlotBeforeCallingRedis() {
        LuaScripts lua = mock(LuaScripts.class);
        StateLogMapper stateLog = mock(StateLogMapper.class);
        RollbackService service = new RollbackService(lua, stateLog);
        CompensateRollbackMessage valid = validMessage();
        CompensateRollbackMessage forged = new CompensateRollbackMessage(
                valid.eventId(), valid.reservationNo(), valid.dupKey(), valid.bucketKey(),
                "slot:full:100", valid.reason(), valid.requestId());

        assertThrows(IllegalArgumentException.class, () -> service.compensate(forged));
        verifyNoInteractions(lua, stateLog);
    }

    @Test
    void rejectsUnknownEventAndReasonBeforeCallingRedis() {
        LuaScripts lua = mock(LuaScripts.class);
        StateLogMapper stateLog = mock(StateLogMapper.class);
        RollbackService service = new RollbackService(lua, stateLog);
        CompensateRollbackMessage valid = validMessage();
        CompensateRollbackMessage forged = new CompensateRollbackMessage(
                "rollback-10", valid.reservationNo(), valid.dupKey(), valid.bucketKey(),
                valid.slotFullKey(), "TEST", valid.requestId());

        assertThrows(IllegalArgumentException.class, () -> service.compensate(forged));
        verifyNoInteractions(lua, stateLog);
    }

    @Test
    void rejectsMalformedDupKeyAndRequestIdBeforeCallingRedis() {
        LuaScripts lua = mock(LuaScripts.class);
        StateLogMapper stateLog = mock(StateLogMapper.class);
        RollbackService service = new RollbackService(lua, stateLog);
        CompensateRollbackMessage valid = validMessage();
        CompensateRollbackMessage forged = new CompensateRollbackMessage(
                valid.eventId(), valid.reservationNo(), "dup:not-a-date:hash", valid.bucketKey(),
                valid.slotFullKey(), valid.reason(), "request id with spaces");

        assertThrows(IllegalArgumentException.class, () -> service.compensate(forged));
        verifyNoInteractions(lua, stateLog);
    }

    private static CompensateRollbackMessage validMessage() {
        return new CompensateRollbackMessage(
                "cr-10", 10L, "dup:2026-08-19:" + "a".repeat(64), "slot:99:b:2",
                "slot:full:99", "ID_CARD_ROUTE_CONFLICT", "request-1");
    }
}
