package com.reservex.config;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 把业务配置真正应用到 RocketMQ Spring 的监听容器。
 *
 * <p>{@code @RocketMQMessageListener} 的线程数、重试次数和批量大小是 int
 * 注解属性，不能写成 Spring 占位符；只校验 {@code reservex.consumer.*}
 * 而不在这里应用，会静默退回 RocketMQ 默认值（尤其是无限重试）。
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RocketMqConsumerConfig implements BeanPostProcessor {

    private final ReserveXProperties props;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DefaultRocketMQListenerContainer container)) {
            return bean;
        }

        DefaultMQPushConsumer consumer = container.getConsumer();
        if (consumer == null) {
            throw new IllegalStateException("RocketMQ listener container has no push consumer: " + beanName);
        }
        ReserveXProperties.Consumer settings = props.getConsumer();
        consumer.setMaxReconsumeTimes(settings.getMaxReconsumeTimes());
        consumer.setConsumeMessageBatchMaxSize(settings.getConsumeMessageBatchMaxSize());

        threadSpec(container.getConsumerGroup()).ifPresent(spec -> {
            consumer.setConsumeThreadMin(spec.getMin());
            consumer.setConsumeThreadMax(spec.getMax());
            log.info("RocketMQ consumer configured group={} threads={}-{} maxReconsumeTimes={} batch={}",
                    container.getConsumerGroup(), spec.getMin(), spec.getMax(),
                    settings.getMaxReconsumeTimes(), settings.getConsumeMessageBatchMaxSize());
        });
        return bean;
    }

    private java.util.Optional<ReserveXProperties.Consumer.ThreadSpec> threadSpec(String group) {
        for (Map.Entry<String, String> entry : props.getConsumer().getGroups().entrySet()) {
            if (entry.getValue().equals(group)) {
                return java.util.Optional.ofNullable(props.getConsumer().getThread().get(entry.getKey()));
            }
        }
        return java.util.Optional.empty();
    }
}
