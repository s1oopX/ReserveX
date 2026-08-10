package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 操作审计 Mapper(**单库**)。
 *
 * <p>⚠️ {@code before} / {@code after} 是 MySQL 保留字,XML 里必须用反引号包起来,
 * 否则语法错误(实体上已用 {@code @TableField("`before`")} 处理 MyBatis-Plus 侧)。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 管理端审计查询:按动作类型翻页。 */
    List<AuditLog> selectByAction(@Param("action") String action,
                                  @Param("limit") Integer limit);
}
