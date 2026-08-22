package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户(**分库表**,分片键 {@code user_id},{@code mod 2} → ds0/ds1)。
 *
 * <p>⚠️ <b>唯一约束不在本表。</b>分库后本地唯一索引只在单个分片内生效 ——
 * 同一邮箱若落到两个不同分片就都能插进去。邮箱/手机/证件的全局唯一由单库的
 * {@code email_route} / {@code phone_route} / {@code id_card_route} 承担(03 §三)。
 * 所以注册是**跨库两写**,顺序与失败处理见 03 §八·补。
 *
 * <p>⚠️ <b>{@code idx_email} 不给登录用。</b>登录走 {@code email_route} 两跳
 * (route 查 {@code user_id} → 按分片键查本表)。直接 {@code WHERE email=?} 会让
 * ShardingSphere 全分片广播,两库各返一行时**随机取一个** —— 用户可能登进别人的账号,
 * 而功能测试(单库数据)完全正常。这个索引只留给运维排查(03 §2.2)。
 *
 * <p>⚠️ <b>{@code role='ADMIN'} 只能由 seed 或 {@code AdminBootstrapRunner} 产生。</b>
 * 注册接口的 role 写死 {@code 'USER'};任何能设 role 的 HTTP 端点本身就是提权漏洞。
 */
@Data
@TableName("user")
public class User {

    /** Snowflake。**分片键**,必须在写库前就确定(要靠它算 mod 2 选库)。 */
    @TableId(type = IdType.INPUT)
    private Long userId;

    private String email;

    private String phone;

    /** BCrypt 串(盐在 hash 内,故无独立 salt 列)。seed 里是哨兵 {@code '!'},见 AdminBootstrapRunner。 */
    private String password;

    /**
     * AES-256-GCM 密文块:{@code iv(12B) || ct || tag(16B)}。
     * 18 位证件 → 46 字节。只经 {@code IdCardCipher} 读写,别处不得自行拼 IV。
     */
    private byte[] idCardCiphertext;

    /** 加密时的密钥版本。没有这一列,"支持密钥轮换"就是空承诺(旧行会 AEADBadTagException)。 */
    private String idCardKeyId;

    /**
     * {@code SHA-256(全局固定 pepper || 明文)},64 位十六进制。
     *
     * <p><b>不是 per-row salt</b> —— 它必须跨用户可比对,{@code id_card_route} 的主键靠它
     * 实现"一人一证一天一约"。加随机盐会让全局唯一失效且功能测试全绿(03 §2.1)。
     */
    private String idCardHash;

    /** 脱敏串。注册时从明文算好落库,**不由密文派生** —— 否则列表页每行都要解密一次。 */
    private String idCardMasked;

    /** {@code USER} / {@code STAFF} / {@code ADMIN}。 */
    private String role;

    /** 0 正常,1 封禁。角色校验、登录与 refresh 每次都回查此状态。 */
    private Integer status;

    /** STAFF 状态资源版本；每次条件更新单调递增。 */
    private Integer version;

    /** 1 表示只能领取一次性改密凭证,成功改密后原子清零。 */
    private Integer mustChangePassword;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;
}
