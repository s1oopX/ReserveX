package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.Slot;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次 Mapper(**单库**)。
 */
public interface SlotMapper extends BaseMapper<Slot> {

    /**
     * 次日场次生成:{@code INSERT IGNORE},靠 {@code uk_date_hour} 挡重跑。
     *
     * <p>⚠️ 必须是 IGNORE 或捕获 DuplicateKey 后**继续下一个模板** ——
     * 任务中断重跑时,若第一个 template 冲突就整批抛异常,
     * 后面三个时段的场次就永远生成不出来(00 §6.2·补5 P0-16)。
     *
     * @return 0 表示该 (date, hour) 已存在,调用方应跳过而非报错
     */
    int insertIgnore(Slot slot);

    /**
     * 放号闸门:{@code released} 0→1 的 CAS。**必须先成功再跑 10.3 Lua。**
     *
     * <p>⚠️ 10.3 用 {@code SET} 覆盖写桶余量,重跑会把已被抢掉的余量**写回满值** =
     * 凭空造库存。所以顺序不能反、不能省(04 §四 / {@link Slot} 类注释)。
     *
     * @return 0 表示已放过号,**绝不能继续执行 10.3**
     */
    int casRelease(@Param("slotId") Long slotId,
                   @Param("version") Integer version);

    /**
     * 增容:{@code capacity += delta}。带 version CAS。
     *
     * <p>⚠️ 只增不减。减容无法实现 —— 已发出的预约不能撤回,
     * 且 Redis 桶余量可能已低于新容量,减了会让不变量 {@code C = A+R+V+X} 直接为负。
     * 三处必须同步:本列、Redis 逐桶 INCRBY、{@code HSET slot:meta capacity}。
     */
    int casIncreaseCapacity(@Param("slotId") Long slotId,
                            @Param("version") Integer version,
                            @Param("delta") Integer delta);

    /**
     * 到点放号扫描:{@code released=0 AND release_at <= now}。
     *
     * <p>⚠️ {@code now} 由应用层按 {@code reservex.zone} 传入,不用 SQL {@code NOW()}
     * (容器时区漏配会差 8h,导致场次提前或延后放号,08 §7.2)。
     */
    List<Slot> selectDueForRelease(@Param("now") LocalDateTime now);

    /** 某日已存在的场次(生成任务查重、用户端列表)。 */
    List<Slot> selectByDate(@Param("slotDate") LocalDate slotDate);
}
