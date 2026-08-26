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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    /**
     * 本文件开头那条红线("每一项都必须在代码里有读取点")对 crons 的机械化断言。
     *
     * <p>两个方向都查,因为两种漂移的表现完全不同:
     * <ul>
     *   <li>yml 有键、代码无读取点 → 改配置**静默不生效**(曾出现过一份与
     *       {@code reservex.slot.gen-cron} 同值的 slot-gen);</li>
     *   <li>代码读、yml 无键 → 真正生效的是 {@code @Scheduled} 里的默认值,
     *       yml 不再是单一真理源,而看 yml 的人得不到正确频率。</li>
     * </ul>
     */
    @Test
    void everyCronKeyHasReaderAndEveryReaderHasCronKey() throws IOException {
        Set<String> declared = Binder.get(loadApplicationYaml())
                .bind("reservex.reconcile.crons",
                        Bindable.mapOf(String.class, String.class))
                .orElseThrow(() -> new AssertionError("reservex.reconcile.crons was not bound"))
                .keySet();
        Set<String> read = cronKeysReadBySource();

        assertEquals(Set.of(), difference(declared, read),
                "yml 声明了但代码没有读取点的 cron(改它不生效)");
        assertEquals(Set.of(), difference(read, declared),
                "代码读取但 yml 未声明的 cron(生效的是 @Scheduled 默认值)");
    }

    private static Set<String> difference(Set<String> from, Set<String> remove) {
        Set<String> result = new TreeSet<>(from);
        result.removeAll(remove);
        return result;
    }

    /** 扫 src/main/java 里的 {@code ${reservex.reconcile.crons.X[:default]}} 占位符。 */
    private static Set<String> cronKeysReadBySource() throws IOException {
        Pattern reader = Pattern.compile("\\$\\{reservex\\.reconcile\\.crons\\.([a-z-]+)");
        Path root = Path.of("src", "main", "java");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = reader.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        if (keys.isEmpty()) {
            throw new AssertionError("没扫到任何 cron 读取点,断言会假绿 —— 检查工作目录与 root=" + root);
        }
        return keys;
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
