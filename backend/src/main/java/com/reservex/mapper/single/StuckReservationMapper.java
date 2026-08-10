package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.StuckReservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡单 Mapper(**单库**)。scanner 补投耗尽后转人工的落点。
 */
@Mapper
public interface StuckReservationMapper extends BaseMapper<StuckReservation> {

    /**
     * 入表。主键 {@code reservation_no} 天然幂等 —— scanner 反复扫到同一笔只留一条。
     *
     * <p>⚠️ <b>必须在 occupy 还没过 TTL 时写</b>:{@code bucket_key} / {@code dup_key}
     * 只存在于 occupy 载荷里,TTL 一过就再也拿不到,桶余量将永久泄漏
     * (见 {@link StuckReservation} 类注释)。
     *
     * @return 0 表示已入表过
     */
    int insertIgnore(StuckReservation stuck);

    /** 对账中心第三个 Tab:待研判列表。 */
    List<StuckReservation> selectPending(@Param("limit") Integer limit);

    /** 人工处置。{@code resolverId} 必填 —— 人工动作必须有责任归属。 */
    int resolve(@Param("reservationNo") Long reservationNo,
                @Param("status") Integer status,
                @Param("resolverId") Long resolverId,
                @Param("resolveAt") LocalDateTime resolveAt);
}
