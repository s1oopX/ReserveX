package com.reservex.mapper.single;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.reservex.entity.IdCardRoute;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一人一证一天一约 Mapper(**单库**)。配额的**第二道防线**。
 *
 * <p>⚠️ <b>复合主键 {@code (id_card_hash, slot_date)} → 不能用
 * {@code selectById} / {@code updateById} / {@code deleteById}</b>,
 * 按主键的操作一律走本接口的自定义方法(见 {@link IdCardRoute} 类注释)。
 */
public interface IdCardRouteMapper extends BaseMapper<IdCardRoute> {

    /**
     * 落库时占配额位。**与预约落库在同一个 singleTxManager 事务里。**
     *
     * <p>⚠️ 这里用 {@code INSERT}(不是 IGNORE):撞主键要**抛异常**,
     * 让消费者走 10.2a 回滚。用 IGNORE 会静默吞掉冲突 ——
     * 结果是配额穿了却无人知晓,而这正是这张表存在的唯一理由。
     */
    int insertQuota(@Param("idCardHash") String idCardHash,
                    @Param("slotDate") LocalDate slotDate,
                    @Param("reservationNo") Long reservationNo,
                    @Param("createAt") LocalDateTime createAt);

    /**
     * 10.2a 回滚:释放配额位。
     *
     * <p>⚠️ 条件必须带 {@code reservation_no} —— 只释放**自己占的**那个位。
     * 只按 (hash, date) 删,会在"同一人当天先取消再重约"的交错下,
     * 把后一笔成功预约的配额位删掉,导致他能约第三次。
     */
    int releaseQuota(@Param("idCardHash") String idCardHash,
                     @Param("slotDate") LocalDate slotDate,
                     @Param("reservationNo") Long reservationNo);

    // ⚠️ 刻意**不提供** selectByHashAndDate 之类"先查有没有再决定要不要占"的方法。
    //    配额唯一性只能由 insertQuota 的主键冲突判定:并发下两个请求都能通过
    //    "查了没有"这一步,查询式判断在这里是纯粹的假保护。
    //    要给用户看"今天约过了吗",走他自己的 reservation 列表,不走这张表。
}
