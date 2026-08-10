package com.reservex.common;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.reservex.config.ReserveXProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 序列化契约(07 §3·补·4)—— 全项目**一处**配置,不逐 DTO 标注。
 *
 * <p><b>① Long 一律序列化成字符串。</b>Snowflake ID 是 19 位十进制,超过
 * JavaScript {@code Number.MAX_SAFE_INTEGER}(2^53 ≈ 9.007e15)。JSON 里以数字形式
 * 传给前端会被 JS **静默改写末几位**:{@code 1234567890123456789} 变成
 * {@code 1234567890123456800}。后果是用户拿着一个不存在的 {@code reservationNo} 去查询/核销,
 * 而**后端日志里的 rno 与前端显示的不是同一个值**,排查时两边对不上。
 *
 * <p>为什么必须在这里配而不是逐 DTO 标 {@code @JsonSerialize}:漏一个 DTO 就是一条
 * 静默错路径,而**功能测试不会红** —— 精度丢失只在 ID 足够大时发生,测试用的小 ID 全对。
 * 这正是 00 §6.3·补 第二档缺陷(永远绿,要靠逐位比对真值才抓得到)的典型。
 *
 * <p><b>② 时区从 {@code reservex.zone} 取,不用 {@code TimeZone.getDefault()}。</b>
 * 后者读容器 TZ,漏配就静默偏 8h(08 §7.2)。
 */
@Configuration
@RequiredArgsConstructor
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    private final ReserveXProperties props;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer reserveXJacksonCustomizer() {
        return builder -> {
            SimpleModule longToString = new SimpleModule();
            // 同时覆盖包装类与基本类型:只配 Long.class 时,int/long 字段(如 slotId 用了 long)
            // 仍会以数字输出,是最常见的漏法
            longToString.addSerializer(Long.class, ToStringSerializer.instance);
            longToString.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modules(longToString);

            builder.timeZone(TimeZone.getTimeZone(props.getZoneId()));
            builder.simpleDateFormat(DATE_TIME_PATTERN);
            builder.serializers(
                    new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(
                            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)),
                    new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer(
                            DateTimeFormatter.ofPattern(DATE_PATTERN)));
            builder.deserializers(
                    new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer(
                            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)),
                    new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer(
                            DateTimeFormatter.ofPattern(DATE_PATTERN)));
        };
    }
}
