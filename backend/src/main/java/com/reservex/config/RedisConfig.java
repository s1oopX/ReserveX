package com.reservex.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 客户端(05 §五)。
 *
 * <p><b>只用 {@link StringRedisTemplate},不用带 JDK 序列化的 RedisTemplate。</b>理由:
 * <ul>
 *   <li>库存侧的值全是整数(桶余量)与短串(occupy 载荷 JSON),JDK 序列化会把
 *       {@code 50} 存成一坨二进制 —— 而 <b>Lua 脚本里的 {@code DECR}/{@code INCR}
 *       只认字符串数字</b>,序列化过的值会让 Lua 直接报错;</li>
 *   <li>{@code redis-cli} 排查时能直接读懂值。库存出问题时第一动作就是去看那几个 key,
 *       看到二进制乱码等于把最重要的排查手段废掉。</li>
 * </ul>
 *
 * <p>⚠️ key 的命名空间隔离靠前缀,不靠分 db(08 §4.6):
 * {@code slot:*} / {@code occupy:*} / {@code dup:*} / {@code pending:*} /
 * {@code ratelimit:*} / {@code captcha:*} / {@code satoken:*}。
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashValueSerializer(StringRedisSerializer.UTF_8);
        // 不开 transactionSupport:本项目的原子性来自 Lua,不来自 MULTI/EXEC。
        // 两者混用会让 Lua 脚本被塞进事务队列而延迟执行,破坏"抢号 2 round-trip"的口径
        template.afterPropertiesSet();
        return template;
    }
}
