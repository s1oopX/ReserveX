package com.reservex.config;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocketMqConsumerConfigTest {

    @Test
    void appliesBusinessConsumerSettingsInsteadOfRocketMqDefaults() {
        ReserveXProperties props = new ReserveXProperties();
        props.getConsumer().setGroups(Map.of("timeout", "cg-timeout"));
        ReserveXProperties.Consumer.ThreadSpec thread = new ReserveXProperties.Consumer.ThreadSpec();
        thread.setMin(4);
        thread.setMax(8);
        props.getConsumer().setThread(Map.of("timeout", thread));
        props.getConsumer().setMaxReconsumeTimes(16);
        props.getConsumer().setConsumeMessageBatchMaxSize(1);

        DefaultRocketMQListenerContainer container = new DefaultRocketMQListenerContainer();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("cg-timeout");
        container.setConsumer(consumer);
        container.setConsumerGroup("cg-timeout");

        new RocketMqConsumerConfig(props).postProcessAfterInitialization(container, "timeoutContainer");

        assertEquals(4, consumer.getConsumeThreadMin());
        assertEquals(8, consumer.getConsumeThreadMax());
        assertEquals(16, consumer.getMaxReconsumeTimes());
        assertEquals(1, consumer.getConsumeMessageBatchMaxSize());
    }
}
