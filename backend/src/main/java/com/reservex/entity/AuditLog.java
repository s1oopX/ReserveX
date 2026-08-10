package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计(单库表)。管理端与敏感动作的留痕。
 *
 * <p>必须写本表的动作(缺一即某类问题无人可查):
 * <ul>
 *   <li>{@code ADMIN_BOOTSTRAP} —— 谁给超管设了初始密码(唯一凭据,08 §4.1);</li>
 *   <li>{@code DECRYPT_IDCARD} —— <b>每一次</b>身份证解密。明文在系统里只应出现在
 *       注册请求体与授权解密两处,不记录等于放弃对明文访问的追溯;</li>
 *   <li>{@code CREATE_STAFF} / {@code BAN_USER} / {@code INCREASE_CAPACITY} /
 *       {@code UPDATE_TEMPLATE} —— 运营动作,{@code before}/{@code after} 存 JSON 摘要。</li>
 * </ul>
 *
 * <p>⚠️ <b>{@code before}/{@code after} 里不得出现身份证明文、密码、密钥。</b>
 * 审计表是"给人看的",一旦写进明文,脱敏就失去意义 —— 存 {@code id_card_masked}
 * 或 hash 前缀即可。
 *
 * <p>⚠️ 本表在 single 库。写它的 {@code @Transactional} 必须显式指定
 * {@code transactionManager = "singleTxManager"},漏写会挂到分片库的 primary 事务管理器上,
 * 现象是"审计偶尔没写进去"而不报错(08 §7.1 红线)。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** {@code USER} / {@code STAFF} / {@code ADMIN} / {@code SYSTEM}。 */
    private String operatorType;

    /** SYSTEM 触发(如启动引导)时为 NULL。 */
    private Long operatorId;

    /** 动作名。见类注释的清单。 */
    private String action;

    private String targetType;

    private Long targetId;

    /** 变更前 JSON 摘要。**不得含明文/密码/密钥。** */
    @TableField("`before`")
    private String before;

    /** 变更后 JSON 摘要。**不得含明文/密码/密钥。** */
    @TableField("`after`")
    private String after;

    /** 链路追踪 id。启动引导等无 HTTP 上下文的场景填固定值(如 {@code "bootstrap"})。 */
    private String requestId;

    private LocalDateTime createAt;
}
