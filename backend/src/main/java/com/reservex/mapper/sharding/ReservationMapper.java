package com.reservex.mapper.sharding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.Reservation;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约 Mapper(**分库**,与 {@code user} 绑定表,归 {@code shardingSqlSessionFactory})。
 *
 * <p>⚠️ <b>查询尽量带 {@code user_id}</b>(分片键)。不带它的查询会广播到两库:
 * <ul>
 *   <li>{@code WHERE reservation_no=?}(详情/核销)**必然广播** —— rno 不是分片键。
 *       这是刻意接受的:核销是低频操作,且 rno 是主键,两库各一次主键查询很快。
 *       <b>但不能在抢号这类高频路径上这么查。</b></li>
 *   <li>批量过期扫描({@code WHERE slot_id=? AND status=0 AND valid_until&lt;NOW()})
 *       同样广播,这也是可接受的:它是定时任务,不在用户等待的路径上。</li>
 * </ul>
 *
 * <p>⚠️ <b>CAS 的不对称是刻意的</b>(01 §3.2 / 04 §五):
 * 取消与过期**不带 {@code version}**(窗口期取消时 DB 里还没记录,读不到 version;
 * 批量过期是多行更新,无法逐行带),只靠 {@code status=0} + {@code valid_until} 守卫;
 * **只有核销带 {@code version=?}**(STAFF 先看详情页再点核销,能挡"页面数据已过期")。
 * 这不是漏写,改成统一带 version 会让窗口期取消无法实现。
 */
public interface ReservationMapper extends BaseMapper<Reservation> {

    /**
     * 主动取消:{@code RESERVED → CANCELLED}。
     *
     * <p>不带 version,见类注释。{@code valid_until >= now} 守卫防"场次已结束还能取消"。
     *
     * @return 0 表示状态已变(重复取消/已核销/已过期),调用方据此返对应错误码
     */
    int cancelByNo(@Param("reservationNo") Long reservationNo,
                   @Param("now") LocalDateTime now);

    /**
     * 核销:{@code RESERVED → VERIFIED}。**唯一带 version 的 CAS**,见类注释。
     *
     * <p>这是防重复核销的**第一道**防线;{@code verification_log.uk_qr_nonce} 是第二道。
     *
     * @return 0 表示已被核销或页面数据已过期
     */
    int verifyByNo(@Param("reservationNo") Long reservationNo,
                   @Param("version") Integer version,
                   @Param("verifiedAt") LocalDateTime verifiedAt);

    /**
     * 批量过期:{@code RESERVED → EXPIRED}。定时任务调用。
     *
     * <p>⚠️ {@code now} 由应用层按 {@code reservex.zone} 算好传入,**不用 SQL 的 NOW()** ——
     * MySQL 容器时区若漏配成 UTC,{@code NOW()} 比业务时间早 8h,
     * 会把当天还有效的预约全刷成 EXPIRED(08 §7.2)。传参让时区只有一个来源。
     */
    int expireBySlot(@Param("slotId") Long slotId,
                     @Param("now") LocalDateTime now);

    /** 配额冲突时清理阶段 1 已插入但不能成立的预约。 */
    int invalidateByNo(@Param("reservationNo") Long reservationNo,
                       @Param("now") LocalDateTime now);

    /**
     * 提醒邮件候选:{@code status=0}(RESERVED)且 {@code valid_until} 落在 [from, to] 窗口内。
     *
     * <p>⚠️ 按 {@code valid_until} 范围查**必然广播两库**(valid_until 不是分片键)。
     * 这是可接受的:提醒是定时任务,不在用户等待的路径上,且窗口通常只有 30min,
     * 命中行数有限(今日预约 < 200)。
     *
     * <p>提醒发送幂等靠 Redis {@code SET reminder:sent:{slot_date} {rno}} 标记,
     * **不查分库 event 表**:event 表在分库,跨库查更贵,且无简单唯一键。
     *
     * @param from 窗口起点(含)= now
     * @param to   窗口终点(含)= now + ahead-min
     * @return 候选预约(含 user_id 供取邮箱、reservation_no 供邮件正文)
     */
    List<Reservation> selectReminderCandidates(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);
}
