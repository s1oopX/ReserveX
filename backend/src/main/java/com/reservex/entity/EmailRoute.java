package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮箱路由(**单库表**)。一张表担两件事,两件都不可省:
 *
 * <p><b>① 邮箱全局唯一。</b>{@code user} 分库后本地唯一索引只在单个分片内生效 ——
 * 同一邮箱落到 ds0 与 ds1 会双双插入成功。唯一性只能由这张单库表的主键保证。
 *
 * <p><b>② 登录的分片路由器。</b>登录先查本表拿 {@code user_id},再按分片键查 {@code user}
 * (03 §2.2 两跳)。若直接 {@code WHERE email=?} 查分库表,ShardingSphere 会**全分片广播**,
 * 两库各返一行时随机取一个 —— 用户可能登进别人的账号,而单库测试数据下功能测试全绿。
 *
 * <p>⚠️ <b>注册是跨库两写</b>:先写本表(抢唯一性,失败即邮箱已占),再写 {@code user}。
 * 顺序反了会留下"user 有行但 route 无行"的幽灵账号 —— 它登不进去(第一跳查不到),
 * 却占着邮箱不让别人注册。顺序与失败补偿见 03 §八·补。
 *
 * <p>⚠️ 主键是 {@code email} 而非自增 id:{@code IdType.INPUT} + String 主键。
 */
@Data
@TableName("email_route")
public class EmailRoute {

    @TableId(type = IdType.INPUT)
    private String email;

    /** 指向 {@code user.user_id}(分片键)。登录第二跳靠它选库。 */
    private Long userId;

    private LocalDateTime createAt;
}
