package com.reservex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 手机号路由(**单库表**)。与 {@link EmailRoute} 同构,承担手机号的全局唯一。
 *
 * <p>v1 不支持手机号登录,故本表只用于**注册时占位去重**,不做路由读。
 * 但它仍是注册跨库两写的一环:注册要同时抢 {@code email_route} 与本表,
 * 任一失败都不能留下 {@code user} 行(03 §八·补)。
 *
 * <p>⚠️ 三张 route 表的写入分属两个库({@code user} 在分库、route 在单库),
 * **跨库无本地事务**。这是 Q1-B 路线下认下的一致性缺口,靠"先 route 后 user +
 * 失败补偿 + routeA/routeB 两类对账"收敛,不是靠分布式事务。
 */
@Data
@TableName("phone_route")
public class PhoneRoute {

    @TableId(type = IdType.INPUT)
    private String phone;

    private Long userId;

    private LocalDateTime createAt;
}
