package com.reservex.worker;

import com.reservex.common.RequestIdFilter;
import com.reservex.lua.LuaScripts;
import com.reservex.mapper.single.StateLogMapper;
import com.reservex.message.CompensateRollbackMessage;
import com.reservex.service.ReservationService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RocketMQMessageListener(topic = "compensate-rollback",
        consumerGroup = "${reservex.consumer.groups.rollback}")
public class RollbackConsumer implements RocketMQListener<CompensateRollbackMessage> {

    private final LuaScripts lua;
    private final StateLogMapper stateLogMapper;

    public RollbackConsumer(LuaScripts lua, StateLogMapper stateLogMapper) {
        this.lua = lua;
        this.stateLogMapper = stateLogMapper;
    }

    @Override
    public void onMessage(CompensateRollbackMessage message) {
        MDC.put(RequestIdFilter.MDC_KEY, message.requestId());
        try {
            lua.evalLong(LuaScripts.Script.COMPENSATE, List.of(message.bucketKey()),
                    Long.toString(message.reservationNo()), message.dupKey(),
                    ReservationService.PENDING_KEY, message.slotFullKey());
            stateLogMapper.insertOrCancel("rx-" + message.reservationNo(),
                    Long.toString(message.reservationNo()));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }
}
