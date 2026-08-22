package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.IdCardIdentity;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 身份证到账号的全局唯一归属，使用 singleDataSource。 */
public interface IdCardIdentityMapper extends BaseMapper<IdCardIdentity> {

    int insertIgnore(@Param("idCardHash") String idCardHash,
                     @Param("userId") Long userId,
                     @Param("createAt") LocalDateTime createAt);

    int deleteByHashAndUser(@Param("idCardHash") String idCardHash,
                            @Param("userId") Long userId);

    List<IdCardIdentity> selectOrphansOlderThan(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("afterCreateAt") LocalDateTime afterCreateAt,
            @Param("afterIdCardHash") String afterIdCardHash,
            @Param("limit") int limit);
}
