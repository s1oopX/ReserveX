package com.reservex.config;

import io.github.resilience4j.common.circuitbreaker.configuration.CommonCircuitBreakerConfigurationProperties.InstanceProperties;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationYamlBindingTest {

    @Test
    void consumerGroupsRemainUnderReserveX() throws IOException {
        ReserveXProperties.Consumer consumer = Binder.get(loadApplicationYaml())
                .bind("reservex.consumer", Bindable.of(ReserveXProperties.Consumer.class))
                .orElseThrow(() -> new AssertionError("reservex.consumer was not bound"));

        assertEquals("cg-timeout", consumer.getGroups().get("timeout"));
    }

    @Test
    void smtpCircuitBreakerUsesTheProductionThresholds() throws IOException {
        CircuitBreakerProperties properties = Binder.get(loadApplicationYaml())
                .bind("resilience4j.circuitbreaker", Bindable.of(CircuitBreakerProperties.class))
                .orElseThrow(() -> new AssertionError("resilience4j.circuitbreaker was not bound"));
        InstanceProperties smtp = properties.getInstances().get("smtp");

        assertEquals(20, smtp.getSlidingWindowSize());
        assertEquals(20, smtp.getMinimumNumberOfCalls());
        assertEquals(50.0f, smtp.getFailureRateThreshold());
        assertEquals(Duration.ofSeconds(3), smtp.getSlowCallDurationThreshold());
        assertEquals(50.0f, smtp.getSlowCallRateThreshold());
        assertEquals(Duration.ofSeconds(30), smtp.getWaitDurationInOpenState());
        assertEquals(2, smtp.getPermittedNumberOfCallsInHalfOpenState());
    }

    private ConfigurableEnvironment loadApplicationYaml() throws IOException {
        ConfigurableEnvironment environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
