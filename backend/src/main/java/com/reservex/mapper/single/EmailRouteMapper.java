package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.EmailRoute;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 邮箱路由 Mapper(**单库**,归 {@code singleSqlSessionFactory})。
 *
 * <p><b>登录第一跳走这里</b>:{@code email → user_id},再拿 user_id 按分片键查
 * {@code user}(03 §2.2)。绝不在分库表上按 email 查(见 {@code sharding.UserMapper} 注释)。
 */
public interface EmailRouteMapper extends BaseMapper<EmailRoute> {

    /**
     * 注册第一步:抢邮箱唯一性。
     *
     * <p>⚠️ 用 {@code INSERT IGNORE} 而非"先 select 再 insert" —— 后者在并发下
     * 两个请求都能通过检查,唯一性只能靠**写入本身**保证。
     *
     * @return 0 表示邮箱已被占用
     */
    int insertIgnore(@Param("email") String email,
                     @Param("userId") Long userId,
                     @Param("createAt") LocalDateTime createAt);

    /**
     * 注册失败补偿:删掉自己刚抢下的 route 行。
     *
     * <p>⚠️ 条件必须带 {@code user_id} —— 只删自己写的那行。
     * 只按 email 删会在极端交错下删掉别人成功注册的行(03 §八·补)。
     */
    int deleteByEmailAndUser(@Param("email") String email, @Param("userId") Long userId);
}
