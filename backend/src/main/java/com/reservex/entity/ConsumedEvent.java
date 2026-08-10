package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费幂等表(单库表)。RocketMQ 是**至少一次**投递,重复消费是常态而非异常。
 *
 * <p>⚠️ <b>复合主键 {@code (consumer_group, event_id)}</b>,MyBatis-Plus 不支持复合
 * {@code @TableId} → 本类**不标注** {@code @TableId},靠 XML resultMap。
 * **不能用 {@code selectById} / {@code updateById}**。
 *
 * <p>⚠️ <b>{@code consumer_group} 是主键前缀,视为不可变常量。</b>改组名等于幂等历史
 * 全部失效 —— 新组名查不到任何已消费记录,存量消息(重投的、堆积的)会被**全部重新消费一遍**。
 * 落库消费者重放意味着重复插预约(靠 rno 主键挡住,尚可);但对账/提醒类消费者重放
 * 会重复发邮件、重复写对账流水。故组名一旦上线不许改(03 §七)。
 *
 * <p>⚠️ <b>{@code event_id} 是确定性派生的,不是随机 UUID</b>:
 * {@code 'rc-' + rno}(reservation-created)、{@code 'sr-' + slot_id}(slot-release)。
 * 随机 id 会让"同一业务动作的两次投递"拿到两个不同 event_id → 幂等表根本拦不住,
 * 而这正是它存在的全部意义(06 §4.2)。
 *
 * <p><b>写入时机的判据</b>(06 §4.1,全项目通用纪律):
 * 重复执行一次有唯一键/CAS 能变 no-op → **成功后写**;没有(如发邮件这种界外调用)
 * → **动作前写**。落库消费者属前者,提醒消费者属后者。
 */
@Data
@TableName("consumed_event")
public class ConsumedEvent {

    /** 消费者组名。不可变常量,见类注释。 */
    private String consumerGroup;

    /** 确定性派生的事件 id,见类注释。 */
    private String eventId;

    private LocalDateTime consumedAt;
}
