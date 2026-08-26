package com.reservex.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code application.yml} 是配置的单一真理源 —— 本测试机械化地钉住这一点。
 *
 * <p>本文件开头那条红线只写了一个方向("每一项都必须在代码里有读取点"),
 * 但**反方向同样是缺陷**:字段有默认值而 yml 不声明时,运维照着 yml 调参会找不到那一项,
 * 于是真正生效的是 Java 里的字段初始值。已经因此漏过两次:
 * {@code reconcile.crons.registration-outbox} 与整个 {@code risk} 块。
 *
 * <p>只覆盖标量字段(String/数字/布尔)。Map 型(datasource / crons / consumer.groups)
 * 与嵌套池规格由各自的专项测试盯,因为它们的键集是动态的。
 */
class PropertyCoverageTest {

    /** 刻意不在 yml 声明的字段:值由环境/运行时决定,写死反而误导。 */
    private static final Set<String> INTENTIONALLY_ABSENT = Set.of(
            // 密钥由 .env 注入成环境变量,yml 里只有 ${VAR} 占位,不算"声明值"。
            "reservex.aes.key-id",
            "reservex.id-hash.pepper",
            "reservex.qr.key-id",
            // WORKER_ID 走环境变量;prod 必须显式给,非 prod 才回落。
            "reservex.id.worker-id",
            "reservex.id.datacenter-id"
    );

    @Test
    void everyScalarPropertyIsDeclaredInApplicationYaml() throws IOException {
        Set<String> declared = yamlKeys();
        Set<String> missing = new TreeSet<>();
        collect(ReserveXProperties.class, "reservex", declared, missing, 0);

        assertEquals(Set.of(), missing,
                "这些属性有 Java 默认值但 yml 未声明 —— 运维在单一真理源里找不到它们");
    }

    private static void collect(Class<?> type, String prefix, Set<String> declared,
                                Set<String> missing, int depth) {
        if (depth > 4) {
            return;
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            String key = prefix + "." + kebab(field.getName());
            Class<?> fieldType = field.getType();
            if (isScalar(fieldType)) {
                if (!declared.contains(key) && !INTENTIONALLY_ABSENT.contains(key)) {
                    missing.add(key);
                }
            } else if (fieldType.getName().startsWith("com.reservex")) {
                collect(fieldType, key, declared, missing, depth + 1);
            }
            // Map/List 型跳过:键集动态,由专项测试覆盖。
        }
    }

    private static boolean isScalar(Class<?> type) {
        return type == String.class || type == Duration.class || type.isPrimitive()
                || Number.class.isAssignableFrom(type) || type == Boolean.class;
    }

    private static String kebab(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Set<String> yamlKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                keys.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        Set<String> normalized = new TreeSet<>();
        for (String key : keys) {
            // 展平后的键形如 reservex.ratelimit.user-redis-rps;Map 键带 [x] 或点号段。
            normalized.add(key);
        }
        if (normalized.stream().noneMatch(k -> k.startsWith("reservex."))) {
            throw new AssertionError("没读到任何 reservex.* 键,断言会假绿");
        }
        return normalized;
    }

    /** 顺带钉住:Map 型键集由专项测试覆盖,这里只确认它们确实被声明了。 */
    @Test
    void mapBackedPropertiesAreDeclared() throws IOException {
        Set<String> declared = yamlKeys();
        for (String required : List.of(
                "reservex.datasource.ds0.url",
                "reservex.datasource.ds1.url",
                "reservex.datasource.single.url",
                "reservex.consumer.groups.persistence",
                "reservex.reconcile.crons.reconcile-a",
                "reservex.aes.keys.aes-v1",
                "reservex.qr.keys.qr-v1")) {
            assertEquals(true, declared.contains(required), "yml 缺少 " + required);
        }
    }

    /** 报告用:把 yml 里 reservex.* 的键数打出来,便于人眼核对规模。 */
    @Test
    void yamlExposesTheExpectedConfigSurface() throws IOException {
        Map<Boolean, List<String>> split = yamlKeys().stream()
                .collect(java.util.stream.Collectors.partitioningBy(k -> k.startsWith("reservex.")));
        assertEquals(true, split.get(true).size() >= 60,
                "reservex.* 键数异常少(" + split.get(true).size() + "),疑似 yml 被截断");
    }
}
