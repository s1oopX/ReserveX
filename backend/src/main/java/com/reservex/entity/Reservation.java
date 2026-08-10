package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预约(**分库表**,与 {@link User} 是**绑定表**:同 {@code user_id} 必落同库)。
 *
 * <p>绑定表的意义:按 {@code user_id} 查"我的预约"时 join 命中同库,不跨库。
 * ShardingSphere 的 {@code binding-tables} 配置必须包含这两张表,否则 join 变笛卡尔广播。
 *
 * <p>⚠️ <b>不建 {@code uk(id_card_hash, slot_date)}</b>:分库本地唯一索引失效,
 * "一人一证一天一约"由单库 {@code id_card_route} 的主键兜底(03 §三)。
 *
 * <p>⚠️ <b>{@code bucket_no} 必须落库</b>:它是抢号时**实际命中**的桶(借桶时不等于
 * 路由算出的主桶)。对账要靠它把 DB 记录归到正确的桶上;没有它,借桶产生的预约在
 * 库存对账里会被算到主桶头上,报出一对正负相消的假 diff。
 *
 * <p>⚠️ <b>{@code valid_until} 是固化字段</b>,不由 {@code slot} 实时算。
 * 模板改了 {@code duration_min} 不应改变已生成场次的有效期(copy-not-reference,03 §9.1)。
 *
 * <p><b>CAS 的不对称是刻意的</b>(01 §3.2 / 04 §五):取消与过期**不带 version**,
 * 只靠 {@code status=0} + {@code valid_until} 守卫 —— 窗口期取消时 DB 里还没记录,
 * 读不到 version,带 version 的写法在那条路径根本无法构造;批量过期是多行更新,
 * 也无法逐行带 version。**只有核销带 {@code version=?}**,因为 STAFF 是先看到详情页
 * (已读到 version)再点核销,带上它能挡"页面数据已过期"的误操作。
 */
@Data
@TableName("reservation")
public class Reservation {

    /** Snowflake。同时是 QR 载荷里的 {@code reservationNo}。 */
    @TableId(type = IdType.INPUT)
    private Long reservationNo;

    /** 分片键。 */
    private Long userId;

    private Long slotId;

    /** 冗余,供对账与按日查询(避免每次回查 slot)。 */
    private LocalDate slotDate;

    /** **实际命中**的桶号,借桶时 != 路由主桶。见类注释。 */
    private Integer bucketNo;

    /** 冗余,关联 {@code id_card_route}。 */
    private String idCardHash;

    /** 冗余,列表脱敏展示(避免列表页逐行解密)。 */
    private String idCardMasked;

    /** 0 RESERVED,1 VERIFIED,2 CANCELLED,3 EXPIRED。状态机只有 0 有出边(01 §一)。 */
    private Integer status;

    /** 过期扫描的判据。固化字段,见类注释。 */
    private LocalDateTime validUntil;

    private LocalDateTime verifiedAt;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;

    /** 乐观锁版本。**只有核销路径把它写进 CAS 条件**,见类注释。 */
    private Integer version;
}
