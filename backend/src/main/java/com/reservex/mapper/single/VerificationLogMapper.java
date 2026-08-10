package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.VerificationLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 核销流水 Mapper(**单库**)。防重复核销第二道防线。
 *
 * <p>⚠️ <b>失败记录走 {@code attempt_nonce},成功记录走 {@code qr_nonce}</b> ——
 * 两者不能混。把真实 nonce 写进带唯一键的 {@code qr_nonce},会让重放的第二次
 * 撞 {@code uk_qr_nonce} 插不进去,**攻击行为反而没留下痕迹**
 * (见 {@link VerificationLog} 类注释)。
 */
public interface VerificationLogMapper extends BaseMapper<VerificationLog> {

    /**
     * 成功核销:填 {@code qr_nonce},承担唯一性。
     *
     * <p>⚠️ 撞 {@code uk_qr_nonce} 说明该 nonce 已被用过(重放),
     * 调用方须捕获并转为 {@code result=1} 的失败记录,**不能让异常直接冒到用户**。
     */
    int insertSuccess(VerificationLog log);

    /**
     * 失败/重放:真实 nonce 记 {@code attempt_nonce}(非唯一),{@code qr_nonce} 留 NULL。
     * MySQL 唯一索引允许多 NULL,故可无限次插入。
     */
    int insertAttempt(VerificationLog log);

    /** 核销台按预约查历史(游客说"扫不了"时的证据来源)。 */
    List<VerificationLog> selectByReservation(@Param("reservationNo") Long reservationNo);
}
