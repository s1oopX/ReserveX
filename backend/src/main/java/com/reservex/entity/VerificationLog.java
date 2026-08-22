package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 核销流水(单库表)。防重复核销的**第二道**防线,第一道是
 * {@code reservation} 上带 {@code version} 的 CAS(01 §3.2)。
 *
 * <p>⚠️ <b>{@code qr_nonce} 可 NULL,且必须可 NULL</b> —— 这一列的设计是本项目里
 * "每个唯一键都要问失败/重试路径会不会撞它连日志都写不下"的反面教材修正(README 纪律 #4):
 * <ul>
 *   <li>手工核销(STAFF 输入预约号)**根本没有 nonce**,NOT NULL 就写不进日志;</li>
 *   <li>更要命的是**重放攻击**:同一个 nonce 第二次来,业务上要拒绝并**记录这次尝试**。
 *       若真实 nonce 写进带唯一键的 {@code qr_nonce},第二条日志会撞 {@code uk_qr_nonce}
 *       插不进去 —— <b>攻击行为反而因为防重放机制而没留下痕迹</b>。</li>
 * </ul>
 * 故:{@code qr_nonce} 只在 {@code result=0}(成功)时填,承担唯一性;
 * 失败/重放把真实 nonce 记到 {@code attempt_nonce}(**非唯一**),两列分工。
 * MySQL 的唯一索引允许多个 NULL,所以唯一性对成功记录仍然成立。
 *
 * <p>⚠️ <b>{@code result} 要区分四种失败原因</b>(1 重复 / 2 已取消 / 3 已过期 / 4 无效凭据),
 * 不能笼统记"失败"。现场排查时"游客说扫不了"必须能立刻分清是他自己取消过、
 * 还是超时了、还是码被人抢先用了 —— 这是核销台唯一的证据来源。
 */
@Data
@TableName("verification_log")
public class VerificationLog {

    @TableId(type = IdType.INPUT)
    private Long verifyId;

    private Long reservationNo;

    /** 操作的工作人员。手工核销时尤其重要:它是唯一的责任归属。 */
    private Long staffId;

    /** 0 扫码,1 手工。 */
    private Integer method;

    /** **仅 {@code result=0} 时填**,承担 {@code uk_qr_nonce} 唯一性。见类注释。 */
    private String qrNonce;

    /** 失败/重放时记录的真实 nonce,**非唯一**。见类注释。 */
    private String attemptNonce;

    /** 0 成功,1 重复,2 已取消,3 已过期,4 无效 QR/手工凭据。 */
    private Integer result;

    private LocalDateTime verifyTime;
}
