package com.reservex.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 失败路径计数器(08 §6.1)。
 *
 * <p>本类只数**补偿链路上的事件**,不数业务成功量 —— 成功量 Actuator 自带的
 * {@code http_server_requests} 已经覆盖。这里要解决的是另一个问题:
 * 本系统的正确性靠补偿(补投 / 对账 / 死信重放)兜底,而补偿成功时静默、
 * 失败时只有一行 stdout。没有这些计数器,「补偿在正常工作」和
 * 「补偿一直在失败」从外部完全无法区分。
 *
 * <p>⚠️ tag 值必须是**有界枚举**。Prometheus 按 name+tags 组合建时间序列,
 * 把 rno、错误堆栈这类自由文本当 tag 会打爆存储。所以 reason/outcome
 * 一律取本类的常量,调用方不要直接把异常消息塞进来。
 *
 * <p>⚠️ 计数器只回答「发生了几次」。要判断「现在积压多少」得看
 * {@link ReserveXHealthGauges} 的 gauge —— 计数器重启归零,存量不会。
 * 两者是配套的,只上一半会得到「没有新增死信」的假安心。
 */
@Component
public class ReserveXMetrics {

    /** 预约转卡单(人工介入前的终点)。tag: reason */
    public static final String STUCK_INTAKE = "reservex.stuck.intake";
    /** PendingScanner 补投尝试。tag: outcome */
    public static final String REINJECT_TOTAL = "reservex.reinject.total";
    /** RocketMQ 重试耗尽后落到 dead_letter_message 的消息。tag: source_group */
    public static final String DEADLETTER_CAPTURED = "reservex.deadletter.captured";
    /** 同步发送 MQ 失败(消息已算持久证据,失败=等补投)。tag: topic */
    public static final String MQ_SEND_FAILED = "reservex.mq.send.failed";
    /** 触发库存回补补偿。tag: reason */
    public static final String COMPENSATE_TRIGGERED = "reservex.compensate.triggered";

    /** 补投次数耗尽(reinject-max),转卡单。 */
    public static final String REASON_REINJECT_EXHAUSTED = "reinject-exhausted";
    /** occupy 里的 user_id 查不到用户,转卡单。 */
    public static final String REASON_USER_MISSING = "user-missing";
    /** 身份证路由落在别的 rno 上,预约作废并回补库存。 */
    public static final String REASON_ID_CARD_ROUTE_CONFLICT = "id-card-route-conflict";

    private final MeterRegistry registry;

    public ReserveXMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 丢弃一切的实例,只给不关心埋点的单测用。
     *
     * <p>⚠️ 生产代码不要用它:注入 noop 等于把这一路的告警静默掉,
     * 正是本类要修的那类 bug。空 {@link CompositeMeterRegistry} 无子注册表,
     * 计数直接落地不占内存。
     */
    public static ReserveXMetrics noop() {
        return new ReserveXMetrics(new CompositeMeterRegistry());
    }

    public void stuckIntake(String reason) {
        inc(STUCK_INTAKE, "预约转卡单数(需人工研判)", "reason", reason);
    }

    public void reinjectSent() {
        inc(REINJECT_TOTAL, "PendingScanner 补投尝试数", "outcome", "sent");
    }

    public void reinjectFailed() {
        inc(REINJECT_TOTAL, "PendingScanner 补投尝试数", "outcome", "failed");
    }

    public void deadLetterCaptured(String sourceGroup) {
        inc(DEADLETTER_CAPTURED, "死信落库数(重试已耗尽)", "source_group", sourceGroup);
    }

    public void mqSendFailed(String topic) {
        inc(MQ_SEND_FAILED, "同步发送 MQ 失败数", "topic", topic);
    }

    public void compensateTriggered(String reason) {
        inc(COMPENSATE_TRIGGERED, "库存回补补偿触发数", "reason", reason);
    }

    /**
     * 注册表按 name+tags 缓存 meter,重复 builder 调用只是一次查表,
     * 不必在字段里预先摊平所有 tag 组合。
     */
    private void inc(String name, String description, String tagKey, String tagValue) {
        Counter.builder(name)
                .description(description)
                .tag(tagKey, tagValue)
                .register(registry)
                .increment();
    }
}
