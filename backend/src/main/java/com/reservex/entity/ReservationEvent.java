package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约事件流水(单库表)。**不可变**:只插不改不删。
 *
 * <p>与 {@link StateLog} 的分工是刻意的、不可合并的:
 * <ul>
 *   <li>{@code state_log} 管**事务边界** —— 这笔能不能继续推进(Try/Confirm/Cancel);</li>
 *   <li>本表管**业务审计** —— 谁在什么时候把它从什么状态改成了什么状态。</li>
 * </ul>
 * 合并会让"回滚判据"与"审计追溯"互相污染:前者要能被更新(状态在推进),
 * 后者必须不可变(改了就不叫审计了)。
 *
 * <p>⚠️ <b>{@code request_id} 是 NOT NULL</b>,而消费者线程与请求线程不是同一根,
 * MDC 不会自动传过去。**消息体里必须带上它**,消费者取出后 {@code MDC.put} 再落库
 * (见 {@code common/RequestIdFilter} 类注释)。这一列填不出来,是 README 纪律 #4
 * "每个 NOT NULL 列都要问所有写入路径拿什么填它"的典型漏点。
 *
 * <p>⚠️ 本表与预约落库**在同一个本地事务**里提交(都在 single 库)。
 * 分开提交会出现"预约落了但没有事件"的空档,而这段空档正是排查时最想看的。
 */
@Data
@TableName("reservation_event")
public class ReservationEvent {

    @TableId
    private String eventId;

    private Long reservationNo;

    /** {@code CREATED} / {@code VERIFIED} / {@code CANCELLED} / {@code EXPIRED}。 */
    private String eventType;

    /** 迁移前状态。{@code CREATED} 时为 NULL(此前不存在)。 */
    private Integer fromStatus;

    private Integer toStatus;

    /** {@code USER} / {@code STAFF} / {@code ADMIN} / {@code SYSTEM}(定时任务与消费者用 SYSTEM)。 */
    private String operatorType;

    /** SYSTEM 触发时为 NULL。 */
    private Long operatorId;

    /** 贯穿全链路的追踪 id。见类注释。 */
    private String requestId;

    private LocalDateTime eventTime;
}
