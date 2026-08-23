package com.reservex.worker;

import com.reservex.common.RequestIdFilter;
import com.reservex.common.TimeSupport;
import com.reservex.mapper.single.StuckReservationMapper;
import com.reservex.message.CompensateRollbackMessage;
import com.reservex.service.RollbackService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = "compensate-rollback",
        consumerGroup = "${reservex.consumer.groups.rollback}")
public class RollbackConsumer implements RocketMQListener<CompensateRollbackMessage> {

    private final RollbackService rollbackService;
    private final StuckReservationMapper stuckMapper;
    private final TimeSupport time;

    public RollbackConsumer(RollbackService rollbackService,
                            StuckReservationMapper stuckMapper,
                            TimeSupport time) {
        this.rollbackService = rollbackService;
        this.stuckMapper = stuckMapper;
        this.time = time;
    }

    @Override
    public void onMessage(CompensateRollbackMessage message) {
        MDC.put(RequestIdFilter.MDC_KEY, message.requestId());
        try {
            if (!rollbackService.compensate(message)) {
                throw new IllegalStateException("rollback occupy 不存在且无 done marker rno="
                        + message.reservationNo());
            }
            stuckMapper.resolveAutomatically(message.reservationNo(), 2, time.now());
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

}
