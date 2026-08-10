package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.SlotTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 场次模板 Mapper(**单库**)。生成任务的数据源。
 *
 * <p>⚠️ 运行期一律读本表,**不读 {@code reservex.slot.seed.*}** ——
 * 那组 yml 只在 {@code 02-seed.sql} 灌种子时用一次(03 §4.0 / 08 §7.1 红线)。
 */
@Mapper
public interface SlotTemplateMapper extends BaseMapper<SlotTemplate> {

    /**
     * 生成任务取模板:**只取 {@code enabled=1}**,按 {@code slot_hour} 排序。
     *
     * <p>排序不是为了美观:生成顺序稳定,重跑时的"跳过哪些"才可预测,
     * 排查"为什么少了一个场次"时能对上日志顺序。
     */
    List<SlotTemplate> selectEnabled();

    /** 管理端改模板,带 version 乐观锁。@return 0 表示并发冲突,让前端重取 */
    int casUpdate(SlotTemplate template);

    /** 停用/启用。**停用不删行**:历史 {@code slot.template_id} 仍指向它。 */
    int setEnabled(@Param("templateId") Long templateId,
                   @Param("enabled") Integer enabled,
                   @Param("version") Integer version,
                   @Param("updateAt") java.time.LocalDateTime updateAt);
}
