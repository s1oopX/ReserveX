package com.reservex.mapper.sharding;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.User;
import org.apache.ibatis.annotations.Param;

/**
 * 用户 Mapper(**分库**,归 {@code shardingSqlSessionFactory})。
 *
 * <p>⚠️ <b>本包下禁止出现按非分片键的查询。</b>ShardingSphere 只能靠 {@code user_id}
 * 定位分片;写 {@code WHERE email=?} 会触发**全分片广播**(ds0 + ds1 各扫一遍再归并):
 * <ul>
 *   <li>不报错,只是每次查询翻倍;</li>
 *   <li>{@code email} 在分库上**没有唯一约束**,广播理论上可能返回两行 →
 *       {@code getOne()} 抛 {@code TooManyResultsException},
 *       而 {@code list().get(0)} <b>会随机登进其中一个账号</b>;</li>
 *   <li>分片数从 2 涨到 N 时线性劣化,恰是分库分表本该避免的反模式。</li>
 * </ul>
 * 邮箱查询属于 {@code mapper.single.EmailRouteMapper} —— 登录走
 * "route 查 user_id → 本表按分片键查" 两跳(03 §2.2)。
 *
 * <p>⚠️ {@code user} 表上的 {@code idx_email} 只留给运维排查(在单个 schema 内直查),
 * 不是给本 Mapper 用的。
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 超管引导专用:把哨兵密码换成真正的 BCrypt。
     *
     * <p>条件里带 {@code password = sentinel} 是**幂等保证**:已引导过或超管已改密时
     * 受影响 0 行,不会覆盖用户自己设的密码。每次启动都无条件重置,会让超管改的密码
     * 在下次重启后静默失效(08 §4.1 坑 2)。
     *
     * @return 受影响行数;0 表示无需引导
     */
    int bootstrapAdminPassword(@Param("userId") Long userId,
                               @Param("sentinel") String sentinel,
                               @Param("bcrypt") String bcrypt,
                               @Param("mustChangePassword") Integer mustChangePassword,
                               @Param("updateAt") java.time.LocalDateTime updateAt);

    int updatePassword(@Param("userId") Long userId,
                       @Param("expectedHash") String expectedHash,
                       @Param("bcrypt") String bcrypt,
                       @Param("updateAt") java.time.LocalDateTime updateAt);

    int updateStatus(@Param("userId") Long userId,
                     @Param("expectedVersion") Integer expectedVersion,
                     @Param("status") Integer status,
                     @Param("updateAt") java.time.LocalDateTime updateAt);
}
