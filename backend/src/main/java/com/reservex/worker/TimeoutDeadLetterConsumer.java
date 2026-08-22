package com.reservex.worker;

import com.reservex.service.DeadLetterService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "%DLQ%${reservex.consumer.groups.timeout}",
        consumerGroup = "${reservex.consumer.groups.dlq-timeout}")
public class TimeoutDeadLetterConsumer implements RocketMQListener<MessageExt> {
    private final DeadLetterService deadLetters;

    @Override
    public void onMessage(MessageExt message) {
        deadLetters.capture("cg-timeout", message);
    }
}
