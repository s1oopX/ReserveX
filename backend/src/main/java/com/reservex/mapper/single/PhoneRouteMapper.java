package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.PhoneRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 手机号路由 Mapper(**单库**)。v1 只用于注册占位去重,不做路由读。
 *
 * <p>注册跨库两写的第二步。它与 {@link EmailRouteMapper} 的失败补偿必须成对存在 ——
 * 邮箱抢到、手机号被占时,要把邮箱那行删回去,否则该邮箱永久不可注册(03 §八·补)。
 */
@Mapper
public interface PhoneRouteMapper extends BaseMapper<PhoneRoute> {

    /** @return 0 表示手机号已被占用 */
    int insertIgnore(@Param("phone") String phone,
                     @Param("userId") Long userId,
                     @Param("createAt") LocalDateTime createAt);

    /** 补偿删除。条件带 {@code user_id},只删自己写的那行。 */
    int deleteByPhoneAndUser(@Param("phone") String phone, @Param("userId") Long userId);
}
