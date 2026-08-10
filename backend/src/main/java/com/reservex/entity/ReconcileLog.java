package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账流水(单库表)。11 类对账任务的统一落点(06 §四)。
 *
 * <p>⚠️ <b>{@code uk_task_period_slot} 是对账任务**自身**的幂等保证。</b>
 * 对账任务由 {@code @Scheduled} 触发,而多实例选主失效、人工补跑、调度抖动都会让
 * 同一任务在同一周期跑两次。没有这个唯一键,同一份差异会被记两条 ——
 * 看板上的"今日差异数"直接翻倍,而运维会以为故障在扩大。
 *
 * <p>⚠️ <b>{@code diff != 0} 默认只告警不自动改。</b>{@code reservex.reconcile.stock-auto-fix}
 * 默认 false(08 §7.1 红线)。自动修的危险在于:对账看到的"不一致"可能是**在途状态**
 * (消息还没消费完),自动修会把一笔正常在途的预约改成"已修复",
 * 而真正的落库随后到达 → 制造出对账自己造成的不一致。开启必须带
 * {@code reconcile:fixing:*} 状态守卫(06 §4.5)。
 *
 * <p><b>三方比对的含义</b>(字段对应):
 * {@code redis_occupied = capacity - Σ Redis 桶余量}、{@code db_occupied = Σ slot_bucket.occupied}、
 * {@code reservation_cnt = 有效预约数(RESERVED + VERIFIED)}。
 * 前两者差 → Redis 与 DB 侧账不一致;后者与前两者差 → 落库丢失或幽灵预约。
 * <b>两个 diff 指向的是不同故障,不能只记一个数。</b>
 */
@Data
@TableName("reconcile_log")
public class ReconcileLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** {@code stock} / {@code routeA} / {@code routeB} / ... 共 11 类。 */
    private String taskType;

    /** 对账周期键({@code slot_date} 或 {@code slot_id + hour})。与 taskType/slotId 共同构成幂等键。 */
    private String period;

    private Long slotId;

    /** {@code capacity - Σ Redis 桶余量}。 */
    private Integer redisOccupied;

    /** {@code Σ slot_bucket.occupied}。 */
    private Integer dbOccupied;

    /** 有效预约数(RESERVED + VERIFIED)。 */
    private Integer reservationCnt;

    private Integer diff;

    /** 采取的动作;只告警时为 NULL。 */
    private String fixAction;

    private LocalDateTime createAt;
}
