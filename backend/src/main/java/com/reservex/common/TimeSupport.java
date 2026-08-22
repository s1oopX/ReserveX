package com.reservex.common;

import com.reservex.config.ReserveXProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 时间的唯一入口(08 §7.2)。
 *
 * <p><b>全项目禁止 {@code ZoneId.systemDefault()} 与 {@code LocalDateTime.now()} 裸调。</b>
 * 前者读容器 {@code TZ},漏配就静默偏 8h 且无任何报错;后者等价于前者。
 * 时区只从 {@code reservex.zone} 一处取,所有按日期派生的 TTL 共用同一 {@link ZoneId}。
 *
 * <p>为什么这个 8 小时特别致命:{@code dup_ttl} 要算"{@code slot_date} 当日 23:59:59 距现在多少秒"。
 * JVM 若在 UTC,它算出的是**北京时间次日 07:59:59** —— dup 多活 8 小时看似无害,
 * 但 {@code slot:full} 与桶 key 用同一派生规则,**桶 key 多活 8h 意味着次日场次开始时
 * 前一天的余量还在**,而放号的 {@code SET} 覆盖恰好又被 CAS 拦住不执行 → 该 slot 用着昨天的桶。
 * 反向(JVM +08:00 而 MySQL UTC)则是过期扫描提前 8h 屠杀有效预约。
 *
 * <p><b>为什么不用 UTC 存、展示层转</b>(工业界更常见的做法):本项目的业务语义本身是
 * **本地日历日** —— "一人一天一次"的"天"是北京时间的天({@code dup:{slot_date}}、
 * {@code id_card_route} PK 里的 {@code slot_date}),{@code slot_hour} 是本地时段。
 * 用 UTC 存会让"跨 UTC 零点的本地同一天"在 {@code slot_date} 上裂成两天,**配额键直接错**。
 * 单区业务用单一本地时区是正解,不是偷懒。
 */
@Component
@RequiredArgsConstructor
public class TimeSupport {

    private final ReserveXProperties props;

    public ZoneId zone() {
        return props.getZoneId();
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    /** 转 unix 秒。Redis 的 {@code EXPIREAT} 与 QR 载荷的 {@code exp} 都用它。 */
    public long toEpochSecond(LocalDateTime dateTime) {
        return dateTime.atZone(zone()).toEpochSecond();
    }

    /**
     * {@code slot_date} 当日结束时刻(23:59:59)。
     *
     * <p>{@code dup} / {@code slot:full} / 桶 key 的 TTL 都派生自这里 ——
     * **一律派生,不硬编天数**(04 §1.1 的 dup TTL bug 根因就是硬编了 1 天,
     * 导致次日场次的 key 在场次开始前就消失)。
     */
    public LocalDateTime endOfDay(LocalDate slotDate) {
        return slotDate.atTime(LocalTime.of(23, 59, 59));
    }

    /**
     * 距 {@code slotDate} 当日结束还有多少秒,带上限兜底。
     *
     * @param capDays 上限天数({@code reservex.redis-key.dup-ttl-cap-days}),
     *                防远期 slot 把 key 压太久
     * @return 至少 1 秒 —— 返回 0 或负数会让 Redis 的 {@code EXPIRE} 立刻删掉刚写的 key,
     *         那等于判重与库存瞬间失效,是比"多活 8h"更急的故障
     */
    public long ttlUntilEndOfDay(LocalDate slotDate, int capDays) {
        long seconds = Duration.between(now(), endOfDay(slotDate)).getSeconds();
        long cap = Duration.ofDays(capDays).getSeconds();
        return Math.max(1L, Math.min(seconds, cap));
    }

    /**
     * 由 {@code slot_date + slot_hour + duration_min} 算 {@code valid_until}。
     *
     * <p>算出来后**落库固化**,不在读路径重算 —— 模板改了 {@code duration_min}
     * 不应影响已生成的场次(03 §9.1:copy-not-reference)。
     */
    public LocalDateTime validUntil(LocalDate slotDate, int slotHour, int durationMin) {
        return slotDate.atTime(LocalTime.of(slotHour, 0)).plusMinutes(durationMin);
    }

    /**
     * 由模板的 {@code release_offset_min} 算放号时刻。
     *
     * <p>偏移相对 {@code slot_date 00:00}:{@code -840} = 前一日 10:00。
     * **模板存偏移而不存时刻**,因为模板不绑定具体日期(03 §4.0)。
     */
    public LocalDateTime releaseAt(LocalDate slotDate, int releaseOffsetMin) {
        return slotDate.atStartOfDay().plusMinutes(releaseOffsetMin);
    }
}
