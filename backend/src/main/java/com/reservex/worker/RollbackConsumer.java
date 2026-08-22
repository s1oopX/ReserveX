package com.reservex.worker;

import com.reservex.common.RequestIdFilter;
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

    public RollbackConsumer(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @Override
    public void onMessage(CompensateRollbackMessage message) {
        MDC.put(RequestIdFilter.MDC_KEY, message.requestId());
        try {
            if (!rollbackService.compensate(message)) {
                throw new IllegalStateException("rollback occupy 不存在且无 done marker rno="
                        + message.reservationNo());
            }
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }
    }

}
