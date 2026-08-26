package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.StuckReservation;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡单 Mapper(**单库**)。scanner 补投耗尽后转人工的落点。
 */
public interface StuckReservationMapper extends BaseMapper<StuckReservation> {

    /**
     * 入表。主键 {@code reservation_no} 天然幂等 —— scanner 反复扫到同一笔只留一条。
     *
     * <p>⚠️ <b>必须在 occupy 还在时写</b>:{@code bucket_key} / {@code dup_key}
     * 只存在于 occupy 载荷里。occupy 无 TTL 不会自然消失,但它一旦因 Redis 侧
     * 丢数据或误删而不见,桶余量将永久泄漏(见 {@link StuckReservation} 类注释)。
     * {@code PendingScanner.toStuck} 因此在写表前先断言 occupy 仍存在。
     *
     * @return 0 表示已入表过
     */
    int insertIgnore(StuckReservation stuck);

    /** 对账中心第三个 Tab:待研判列表。 */
    List<StuckReservation> selectPending(@Param("limit") Integer limit);

    /** User read model: keep scanner failures visible after they leave pending ZSet. */
    List<StuckReservation> selectByUser(@Param("userId") Long userId);

    /** 卡单状态 CAS。{@code resolverId} 必填 —— 人工动作必须有责任归属。 */
    int transition(@Param("reservationNo") Long reservationNo,
                   @Param("fromStatus") Integer fromStatus,
                   @Param("toStatus") Integer toStatus,
                   @Param("resolverId") Long resolverId,
                   @Param("resolveAt") LocalDateTime resolveAt);

    /** Closes a scanner row when delayed MQ processing eventually completes. */
    int resolveAutomatically(@Param("reservationNo") Long reservationNo,
                             @Param("toStatus") Integer toStatus,
                             @Param("resolveAt") LocalDateTime resolveAt);
}
